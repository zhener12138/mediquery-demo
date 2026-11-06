package com.mediquery.module.operationlog.aspect;

import com.mediquery.module.operationlog.entity.OperationLog;
import com.mediquery.module.operationlog.mapper.OperationLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * {@link AuditLog} 的切面：方法**成功**之后写一条 {@code sys_operation_log}。
 *
 * <h3>五条刻意的设计决定</h3>
 *
 * <ol>
 *   <li><b>只在成功后记</b>。用 {@code @Around} 先 {@code proceed()} 再写日志，
 *       方法抛异常时一条都不落。理由：审计日志记的是「发生了什么」，
 *       记一条「张三删除了疾病 12」而实际删除因为外键约束失败了，是更坏的结果 ——
 *       它会让人以为数据没了。</li>
 *   <li><b>写日志失败绝不影响业务</b>。整个写入过程 try/catch 包住，只 {@code log.warn}。
 *       日志表写不进去（磁盘满、表被锁）不该让用户的一次完结问诊变成 500。</li>
 *   <li><b>不在事务里就等提交后写</b>。如果被切方法有 {@code @Transactional}，
 *       直接 insert 会进同一个事务 —— 业务回滚时日志也跟着没了（这倒是对的），
 *       但如果业务成功提交而日志 insert 语句出错，会把整个业务事务标记为 rollback-only，
 *       反而把成功的业务废掉。用 {@code afterCommit} 把写入挪到提交之后，两种风险都避开。</li>
 *   <li><b>{@code target} 存 URI 不存参数</b>。参数里可能有密码。
 *       而 {@code DELETE /api/v1/illnesses/12} 这个 URI 本身已经说清了对谁做了什么，
 *       比往日志里塞一坨 JSON 更安全也更好读。</li>
 *   <li><b>取不到用户就记 null，不抛异常</b>。切面可能跑在异步线程（SSE worker）里，
 *       {@code SecurityContextHolder} 在那里是空的。宁可日志少一个 user_id，
 *       也不能因为拿不到登录态把请求打挂。</li>
 * </ol>
 */
@Aspect
@Component
public class AuditLogAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditLogAspect.class);

    /** {@code X-Forwarded-For} 里可能有逗号分隔的多级代理，只取第一段。 */
    private static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";

    /** 各种客户端 IP 头的候选顺序，服务端字段放最后兜底。 */
    private static final String[] IP_HEADERS = {"X-Real-IP", HEADER_FORWARDED_FOR};

    private final OperationLogMapper operationLogMapper;

    public AuditLogAspect(OperationLogMapper operationLogMapper) {
        this.operationLogMapper = operationLogMapper;
    }

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        Object result = joinPoint.proceed();
        // 走到这里说明方法正常返回了，变更已经发生（或至少在事务里会被提交）
        if (returnValueAllows(auditLog, result)) {
            writeQuietly(auditLog, joinPoint);
        }
        return result;
    }

    /**
     * 返回值是否满足「该记日志」的条件。
     *
     * <p>见 {@link AuditLog#returnValueMustBe()}。三种情况分开处理：
     * <ul>
     *   <li>注解没声明 → 一律记。</li>
     *   <li>返回值是 {@code Boolean} → 与声明值比对。</li>
     *   <li>返回值是 {@code null} 或不是 {@code Boolean}（注解写错了）→ <b>不记</b>。</li>
     * </ul>
     *
     * <p>第三类为什么不记：声明了 {@code returnValueMustBe = "true"} 就等于说
     * 「只有明确成功才算数」，而 {@code null} 和错类型都拿不出成功证据。
     * 审计日志少记一条可以补，记一条「关掉了」而其实没关，就没法纠正了。
     */
    static boolean returnValueAllows(AuditLog auditLog, Object result) {
        String expected = auditLog.returnValueMustBe();
        if (expected == null || expected.isBlank()) return true;
        if (!(result instanceof Boolean actual)) return false;
        return String.valueOf(actual).equalsIgnoreCase(expected.trim());
    }

    /**
     * 写日志。任何异常都吞掉，只留 warn —— 详见类注释第 2 条。
     */
    private void writeQuietly(AuditLog auditLog, ProceedingJoinPoint joinPoint) {
        try {
            OperationLog entry = build(auditLog, joinPoint);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                // 有事务：挪到提交之后再写，避免日志写失败把业务事务拖成 rollback-only
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                insertQuietly(entry);
                            }
                        });
            } else {
                insertQuietly(entry);
            }
        } catch (Exception e) {
            log.warn("记录审计日志失败 action={}", auditLog.value(), e);
        }
    }

    private void insertQuietly(OperationLog entry) {
        try {
            operationLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("写入审计日志失败 action={} target={}", entry.getAction(), entry.getTarget(), e);
        }
    }

    OperationLog build(AuditLog auditLog, ProceedingJoinPoint joinPoint) {
        var entry = new OperationLog();
        entry.setAction(auditLog.value());
        entry.setModule(resolveModule(auditLog, joinPoint));

        var auth = currentAuthentication();
        Integer userId = currentUserId(auth);
        entry.setUserId(userId);
        entry.setUsername(auth == null ? null : String.valueOf(auth.getName()));

        HttpServletRequest request = currentRequest();
        if (request != null) {
            entry.setTarget(request.getMethod() + " " + request.getRequestURI());
            entry.setIpAddress(resolveClientIp(request));
        } else {
            // 异步线程（SSE worker）里拿不到请求上下文，退化成方法名
            entry.setTarget(joinPoint.getSignature().toShortString());
        }
        return entry;
    }

    /**
     * 模块名：注解显式给了就用它，否则从被切类的简单名退掉 {@code Controller}/{@code Service} 后缀。
     *
     * <p>{@code ConsultationController → Consultation} 这样的名字给人看时够用，
     * 但不如手写「问诊」准确 —— 所以注解留了 {@code module} 参数。
     */
    static String resolveModule(AuditLog auditLog, ProceedingJoinPoint joinPoint) {
        String declared = auditLog.module();
        if (declared != null && !declared.isBlank()) return declared.trim();

        String simple = joinPoint.getSignature().getDeclaringType().getSimpleName();
        for (String suffix : new String[]{"Controller", "ServiceImpl", "Service"}) {
            if (simple.endsWith(suffix) && simple.length() > suffix.length()) {
                return simple.substring(0, simple.length() - suffix.length());
            }
        }
        return simple;
    }

    /**
     * 当前认证信息。
     *
     * <p>用 {@code getContext().getAuthentication()} 而不是注入 {@code Authentication} 参数 ——
     * 切面方法签名里没有那个参数，硬加会改变被切方法的签名。
     */
    static Authentication currentAuthentication() {
        var ctx = SecurityContextHolder.getContext();
        return ctx == null ? null : ctx.getAuthentication();
    }

    /**
     * 从认证信息里取用户 ID。
     *
     * <p>{@code JwtAuthenticationFilter} 做的是 {@code auth.setDetails(userId)}，
     * 所以这里必须读 {@code getDetails()} 而不是 {@code getPrincipal()} ——
     * principal 存的是 username 字符串。
     */
    static Integer currentUserId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return null;
        Object details = auth.getDetails();
        if (details instanceof Long l) return l.intValue();
        if (details instanceof Integer i) return i;
        return null;
    }

    static HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
    }

    /**
     * 解析客户端 IP。
     *
     * <p>先看代理头再看 {@code remoteAddr}：部署在 nginx 后面时 {@code remoteAddr}
     * 永远是 nginx 自己的地址，全表都是同一个 IP，等于没记。
     *
     * <p><b>注意</b>：这些头是客户端可以伪造的，所以它只有「参考价值」，
     * 不能当安全依据。真要防伪造得在 nginx 上显式覆盖 {@code X-Real-IP}。
     * 本项目 {@code frontend/nginx.conf} 里配的正是 {@code proxy_set_header X-Real-IP $remote_addr}。
     */
    static String resolveClientIp(HttpServletRequest request) {
        for (String header : IP_HEADERS) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value)) {
                int comma = value.indexOf(',');
                return (comma > 0 ? value.substring(0, comma) : value).trim();
            }
        }
        return request.getRemoteAddr();
    }
}
