package com.mediquery.module.operationlog.aspect;

import com.mediquery.module.operationlog.entity.OperationLog;
import com.mediquery.module.operationlog.mapper.OperationLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计切面的行为约束。
 *
 * <p>这里测的不是「日志长得对不对」，而是三条一旦破坏就会出真事的规则：
 * 失败不记、写不进去也不炸、事务里挪到提交后。
 */
class AuditLogAspectTest {

    private OperationLogMapper mapper;
    private AuditLogAspect aspect;

    @BeforeEach
    void setUp() {
        mapper = mock(OperationLogMapper.class);
        when(mapper.insert(any(OperationLog.class))).thenReturn(1);
        aspect = new AuditLogAspect(mapper);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static void bindRequest(String method, String uri, String remoteAddr) {
        var req = new MockHttpServletRequest(method, uri);
        req.setRemoteAddr(remoteAddr);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    private static void bindUser(Long userId, String username) {
        var auth = new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        auth.setDetails(userId);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("成功时记一条：用户、动作、URI、IP 都对得上")
    void recordsOnSuccess() {
        bindRequest("PUT", "/api/v1/consultations/12/complete", "10.1.2.3");
        bindUser(7L, "admin");

        var entry = aspect.build(stubAnnotation("完结问诊", "问诊"), stubJoinPoint());

        assertThat(entry.getAction()).isEqualTo("完结问诊");
        assertThat(entry.getModule()).isEqualTo("问诊");
        assertThat(entry.getUserId()).isEqualTo(7);
        assertThat(entry.getUsername()).isEqualTo("admin");
        assertThat(entry.getTarget()).isEqualTo("PUT /api/v1/consultations/12/complete");
        assertThat(entry.getIpAddress()).isEqualTo("10.1.2.3");
    }

    @Test
    @DisplayName("模块名没写就从类名退：ConsultationController → Consultation")
    void derivesModuleFromClassNameWhenBlank() {
        bindRequest("GET", "/x", "127.0.0.1");

        var entry = aspect.build(stubAnnotation("动作", ""), stubJoinPoint());

        assertThat(entry.getModule()).isEqualTo("StubOnly");
    }

    @Test
    @DisplayName("没有登录态也照记：user_id 为 null，不抛异常")
    void recordsAnonymousWithoutBlowingUp() {
        bindRequest("POST", "/api/v1/consultations", "127.0.0.1");
        // 刻意不设 SecurityContext

        var entry = aspect.build(stubAnnotation("发起问诊", "问诊"), stubJoinPoint());

        assertThat(entry.getUserId()).isNull();
        assertThat(entry.getUsername()).isNull();
        assertThat(entry.getTarget()).isEqualTo("POST /api/v1/consultations");
    }

    @Test
    @DisplayName("代理头优先于 remoteAddr，且只取第一段")
    void forwardedHeaderWinsAndTakesFirstSegment() {
        var req = new MockHttpServletRequest("PUT", "/x");
        req.setRemoteAddr("172.18.0.5"); // 这是 nginx 的地址
        req.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        assertThat(AuditLogAspect.resolveClientIp(req)).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("X-Real-IP 优先于 X-Forwarded-For")
    void realIpBeatsForwardedFor() {
        var req = new MockHttpServletRequest("PUT", "/x");
        req.addHeader("X-Real-IP", "198.51.100.7");
        req.addHeader("X-Forwarded-For", "203.0.113.9");

        assertThat(AuditLogAspect.resolveClientIp(req)).isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("没有任何代理头就退回 remoteAddr")
    void fallsBackToRemoteAddr() {
        var req = new MockHttpServletRequest("PUT", "/x");
        req.setRemoteAddr("192.0.2.44");

        assertThat(AuditLogAspect.resolveClientIp(req)).isEqualTo("192.0.2.44");
    }

    @Test
    @DisplayName("写日志抛异常不能把业务带崩")
    void insertFailureDoesNotBreakBusiness() {
        bindRequest("PUT", "/x", "127.0.0.1");
        when(mapper.insert(any(OperationLog.class)))
                .thenThrow(new RuntimeException("表被锁了"));

        var entry = new OperationLog();
        entry.setAction("完结问诊");

        assertThatCode(() -> invokeInsertQuietly(entry)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("事务活跃时，日志要等提交后才写")
    void defersWriteUntilAfterCommit() {
        bindRequest("PUT", "/x", "127.0.0.1");
        TransactionSynchronizationManager.initSynchronization();

        var entry = new OperationLog();
        entry.setAction("完结问诊");
        invokeWriteQuietly(stubAnnotation("完结问诊", "问诊"), entry);

        // 还没提交：一条都不该写
        verify(mapper, never()).insert(any(OperationLog.class));

        // 模拟提交
        TransactionSynchronizationUtils.invokeAfterCommit(
                TransactionSynchronizationManager.getSynchronizations());

        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("完结问诊");
    }

    @Test
    @DisplayName("事务回滚时日志也不写 —— 不能出现「记了但没发生」")
    void doesNotWriteWhenRolledBack() {
        bindRequest("PUT", "/x", "127.0.0.1");
        TransactionSynchronizationManager.initSynchronization();

        var entry = new OperationLog();
        entry.setAction("完结问诊");
        invokeWriteQuietly(stubAnnotation("完结问诊", "问诊"), entry);

        TransactionSynchronizationUtils.invokeAfterCompletion(
                TransactionSynchronizationManager.getSynchronizations(),
                org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(mapper, never()).insert(any(OperationLog.class));
    }

    // ───── 反射调用私有方法，避免为了测试把它们改成包级 ─────

    private void invokeInsertQuietly(OperationLog entry) {
        try {
            var m = AuditLogAspect.class.getDeclaredMethod("insertQuietly", OperationLog.class);
            m.setAccessible(true);
            m.invoke(aspect, entry);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void invokeWriteQuietly(AuditLog annotation, OperationLog ignored) {
        try {
            var m = AuditLogAspect.class.getDeclaredMethod(
                    "writeQuietly", AuditLog.class, org.aspectj.lang.ProceedingJoinPoint.class);
            m.setAccessible(true);
            m.invoke(aspect, annotation, stubJoinPoint());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ───── 手搓注解与切点：不引 spring-aop-test，也不为一个测试开新依赖 ─────

    private static AuditLog stubAnnotation(String value, String module) {
        return new AuditLog() {
            @Override
            public String value() {
                return value;
            }

            @Override
            public String module() {
                return module;
            }

            @Override
            public String returnValueMustBe() {
                return "";
            }

            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return AuditLog.class;
            }
        };
    }

    private static org.aspectj.lang.ProceedingJoinPoint stubJoinPoint() {
        var jp = mock(org.aspectj.lang.ProceedingJoinPoint.class);
        var sig = mock(org.aspectj.lang.Signature.class);
        when(sig.getDeclaringType()).thenReturn(StubOnly.class);
        when(sig.toShortString()).thenReturn("StubOnly.stub()");
        when(jp.getSignature()).thenReturn(sig);
        return jp;
    }

    /** 只为了 {@code getDeclaringType().getSimpleName()} 有一个可控的类名。 */
    private static class StubOnly {
    }
}
