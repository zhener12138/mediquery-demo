package com.mediquery.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TraceIdFilter 的测试。
 *
 * <p>这里不用 {@code @SpringBootTest}：过滤器的逻辑跟 Spring 容器没关系，
 * 起整个容器测试只会让测试慢十倍，还测不出更多东西。
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesTraceIdAndPutsItInHeaderAndMdc() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/v1/illnesses");
        var resp = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(req, resp, recordingChain(() -> mdcDuringChain.set(MDC.get(TraceIdFilter.TRACE_ID))));

        String header = resp.getHeader(TraceIdFilter.TRACE_ID);
        assertNotNull(header, "响应头里必须有 traceId");
        assertEquals(16, header.length(), "自动生成的 ID 应该是 16 位");
        assertEquals(header, mdcDuringChain.get(), "链路执行期间 MDC 里必须是同一个 ID");
    }

    @Test
    void usesUpstreamHeaderWhenPresent() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/v1/illnesses");
        req.addHeader(TraceIdFilter.UPSTREAM_HEADER, "abc123DEF");
        var resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, recordingChain(null));

        assertEquals("abc123DEF", resp.getHeader(TraceIdFilter.TRACE_ID),
                "上游传了 ID 就必须沿用，否则跨服务链路断掉");
    }

    @Test
    void clearsMdcAfterRequest() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/v1/illnesses");
        var resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, recordingChain(null));

        // Tomcat 线程是复用的。不清干净，下一个请求会带着上一个的 traceId 打日志，
        // 这比完全没有 traceId 更糟——它会把你引到错误的日志上去。
        assertNull(MDC.get(TraceIdFilter.TRACE_ID), "请求结束后必须清理 MDC，否则线程复用会串号");
    }

    @Test
    void clearsMdcEvenWhenChainThrows() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/v1/illnesses");
        var resp = new MockHttpServletResponse();
        FilterChain boom = (rq, rs) -> {
            throw new IllegalStateException("链路里炸了");
        };

        assertThrows(IllegalStateException.class, () -> filter.doFilter(req, resp, boom));
        assertNull(MDC.get(TraceIdFilter.TRACE_ID), "异常路径也必须清理，这正是 finally 存在的理由");
    }

    @Test
    void rejectsHeaderInjectionAttempt() {
        // 日志注入：如果直接把上游传的值拼进日志，攻击者塞个换行就能伪造日志行
        String forged = "abc\n2026-01-01 00:00:00.000 ERROR fake - 数据库已被删除";
        String resolved = TraceIdFilter.resolve(forged);

        assertNotEquals(forged, resolved, "带换行的值必须被拒绝");
        assertFalse(resolved.contains("\n"), "结果里不能有换行");
        assertEquals(16, resolved.length(), "拒绝后回退到自动生成");
    }

    @Test
    void rejectsOverlongHeader() {
        String longId = "a".repeat(200);
        String resolved = TraceIdFilter.resolve(longId);

        assertNotEquals(longId, resolved, "超长 ID 会让日志和响应头爆掉，必须拒绝");
        assertEquals(16, resolved.length());
    }

    @Test
    void acceptsSafeCharactersOnly() {
        assertEquals("a-b_c.1", TraceIdFilter.resolve("a-b_c.1"));
        // 空格、引号、分号这类都不在白名单里
        assertEquals(16, TraceIdFilter.resolve("a b").length());
        assertEquals(16, TraceIdFilter.resolve("a\"b").length());
        assertEquals(16, TraceIdFilter.resolve("").length());
        assertEquals(16, TraceIdFilter.resolve(null).length());
    }

    @Test
    void generatedIdsAreUniquePerRequest() throws Exception {
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            var req = new MockHttpServletRequest("GET", "/api/v1/illnesses");
            var resp = new MockHttpServletResponse();
            filter.doFilter(req, resp, recordingChain(null));
            seen.add(resp.getHeader(TraceIdFilter.TRACE_ID));
        }
        // 50 个请求里出现重复 = ID 生成有问题（概率上极不可能）
        assertEquals(seen.size(), seen.stream().distinct().count(), "并发/连续请求的 traceId 必须互不相同");
    }

    /** 造一个在"执行链"里插一句断言的 FilterChain。 */
    private static FilterChain recordingChain(Runnable duringChain) {
        return (rq, rs) -> {
            if (duringChain != null) duringChain.run();
        };
    }

    /** 让编译器知道 HttpServletRequest / HttpServletResponse 的类型来源，避免未使用告警。 */
    @SuppressWarnings("unused")
    private void typeAnchors(HttpServletRequest a, HttpServletResponse b) {
    }
}
