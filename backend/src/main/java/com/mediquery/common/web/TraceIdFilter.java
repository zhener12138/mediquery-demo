package com.mediquery.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 给每个请求打一个可追踪的 ID，写进日志的每一行，并回给前端。
 *
 * <p>解决什么问题：现在出错了只能看到一条「服务器内部错误」，用户来报障时
 * 你既不知道是哪次请求、也不知道该去日志里搜什么。有了 traceId，
 * 用户截图里的那串 ID 就是日志里的检索关键字。
 *
 * <p>三个设计选择：
 * <ul>
 *   <li>用 {@link OncePerRequestFilter} 而不是普通 Filter —— 异步分发（SSE）会重新
 *       进一次过滤器链，普通 Filter 会重复生成 ID，导致同一个请求前后半段对不上。</li>
 *   <li>ID 优先取上游网关传的 {@code X-Request-Id}，没有才自己生成。生产环境前置
 *       nginx/网关时，只有这样链路才连得起来。</li>
 *   <li>{@code finally} 里 {@code MDC.remove} —— Tomcat 线程是复用的，不清干净
 *       下一个请求会带着上一个的 ID 打日志，比没有 ID 更误导人。</li>
 * </ul>
 *
 * <p>注意 SSE：{@code response.setHeader} 必须在容器提交响应之前执行。
 * 本过滤器在 {@code chain.doFilter} 之前设，是安全的；但如果将来有人把设 header
 * 的代码挪到 doFilter 之后，SSE 接口会直接报 IllegalStateException。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 响应头与 MDC 用的同一个键名，前后端对齐时只认这一个。 */
    public static final String TRACE_ID = "traceId";

    /** 上游传入的请求头名。nginx 里用 {@code proxy_set_header X-Request-Id $request_id;} 注入。 */
    public static final String UPSTREAM_HEADER = "X-Request-Id";

    /** 长度上限：外部传进来的值不能无限长，否则日志被撑爆、响应头也可能超限。 */
    private static final int MAX_LEN = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = resolve(request.getHeader(UPSTREAM_HEADER));
        MDC.put(TRACE_ID, traceId);
        try {
            response.setHeader(TRACE_ID, traceId);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID);
        }
    }

    /**
     * 上游给的 ID 只接受"看起来像 ID"的字符。
     *
     * <p>不直接透传的原因：这个值会被写进日志。如果上游传 {@code abc\n2026-01-01 ERROR ...}，
     * 就能往日志里伪造行（日志注入）。只留字母数字和 {@code - _ .} 已经够用，
     * 而且长度截断到 64。
     */
    static String resolve(String fromUpstream) {
        if (fromUpstream != null) {
            String trimmed = fromUpstream.trim();
            if (!trimmed.isEmpty() && trimmed.length() <= MAX_LEN
                    && trimmed.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.')) {
                return trimmed;
            }
        }
        // 去掉 UUID 里的横线，短一点，日志里也好看
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
