package com.mediquery.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CorsConfig} 的护栏测试。
 *
 * <h3>这些测试在防什么</h3>
 *
 * <p>改之前配置是 {@code setAllowedOriginPatterns(List.of("*"))} + {@code setAllowCredentials(true)}，
 * 任意站点都能跨域读响应。这种配置不会报错、不会告警，一切正常跑，
 * 只有真被利用时才知道——所以必须用测试把"通配"这件事钉死。
 *
 * <p>测试不检查源码字符串（那太脆），而是<b>真的走一遍 CorsFilter</b>，
 * 看它给不同 Origin 回什么头。这是浏览器实际做出的判断依据。
 */
class CorsConfigTest {

    private MockHttpServletResponse preflightWith(String origin, CorsConfig config) throws Exception {
        var request = new MockHttpServletRequest("OPTIONS", "/api/v1/consultations");
        request.addHeader("Origin", origin);
        request.addHeader("Access-Control-Request-Method", "POST");

        var response = new MockHttpServletResponse();
        config.corsFilter().doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    @DisplayName("配置里的源会被放行")
    void allowedOriginIsAccepted() throws Exception {
        var config = new CorsConfig("http://localhost:5173");

        var response = preflightWith("http://localhost:5173", config);

        assertEquals("http://localhost:5173", response.getHeader("Access-Control-Allow-Origin"),
                "白名单里的源应该被回显到 Access-Control-Allow-Origin");
    }

    @Test
    @DisplayName("配置外的源不会拿到许可头")
    void foreignOriginIsRejected() throws Exception {
        var config = new CorsConfig("http://localhost:5173");

        var response = preflightWith("https://evil.example.com", config);

        assertNull(response.getHeader("Access-Control-Allow-Origin"),
                "wildcard 已经拆掉了，陌生源不该拿到 Access-Control-Allow-Origin。"
                        + "如果这里拿到了，浏览器就会允许那个站点读取我们的响应。");
    }

    @Test
    @DisplayName("不允许出现过裸的通配符 *")
    void neverEchoesWildcard() throws Exception {
        var config = new CorsConfig("http://localhost:5173");

        // 连自己白名单里的源也不该回 "*"——回 "*" 就说明又退化成通配了。
        var allowed = preflightWith("http://localhost:5173", config);
        var foreign = preflightWith("https://evil.example.com", config);

        assertFalse("*".equals(allowed.getHeader("Access-Control-Allow-Origin")),
                "Access-Control-Allow-Origin 回了 * 且同时开了 allowCredentials，"
                        + "等于任意站点都能带凭证跨域，这是要防的那个漏洞本身。");
        assertNull(foreign.getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("解析时去掉空格、空项和结尾斜杠")
    void parseNormalizesInput() {
        // 环境变量里写成 "a, b," 是很常见的手滑，不处理就会多出一个空源，
        // 或者因为结尾斜杠导致 Origin 永远匹配不上（浏览器发的 Origin 不带尾斜杠）。
        List<String> parsed = CorsConfig.parse(" http://localhost:5173 , http://127.0.0.1:5173/ ,, ");

        assertEquals(List.of("http://localhost:5173", "http://127.0.0.1:5173"), parsed,
                "空格该去掉、空项该丢掉、结尾斜杠该剥掉——"
                        + "http://a.com/ 和 http://a.com 在 Origin 头里是两个不同字符串");
    }

    @Test
    @DisplayName("支持子域通配，但不接受整个通配")
    void subdomainPatternIsSupported() {
        var config = new CorsConfig("https://*.example.com");

        assertTrue(config.getAllowedOrigins().contains("https://*.example.com"),
                "Spring 的 allowedOriginPatterns 支持 https://*.example.com，"
                        + "生产上按子域部署时要用得上");
    }

    @Test
    @DisplayName("配成空值时启动就失败，而不是静默放行或静默全拒")
    void emptyConfigFailsFast() {
        // 两种错法都比崩掉更糟：静默放行 = 没防护；静默全拒 = 线上跨域全挂，
        // 但没人知道为什么。启动时炸掉，问题当场就暴露在部署日志里。
        assertThrows(IllegalStateException.class, () -> new CorsConfig(""));
        assertThrows(IllegalStateException.class, () -> new CorsConfig(null));
        assertThrows(IllegalStateException.class, () -> new CorsConfig("  , ,, "));
    }

    @Test
    @DisplayName("默认值只覆盖本地开发，不含任何通配")
    void defaultConfigIsDevOnly() {
        // 不带参数的默认值（application.yml 里 ${CORS_ALLOWED_ORIGINS:...} 的兜底）
        // 必须是本地地址。如果哪天有人把默认值改成 "*"，这条会红。
        var config = new CorsConfig("http://localhost:5173,http://127.0.0.1:5173");

        assertEquals(2, config.getAllowedOrigins().size());
        assertTrue(config.getAllowedOrigins().stream().noneMatch(o -> o.equals("*") || o.contains("*")),
                "默认配置里不允许出现通配符");
    }
}
