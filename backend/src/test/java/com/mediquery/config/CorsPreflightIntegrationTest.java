package com.mediquery.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORS 预检的<b>容器级</b>测试：真的起一个 Tomcat，真的发 OPTIONS 请求。
 *
 * <h3>为什么必须有这一层</h3>
 *
 * <p>{@link CorsConfigTest} 是直接调 {@code corsFilter().doFilter(...)} 的单元测试，
 * 它只能证明"过滤器本身逻辑对"，证明不了"过滤器真的被执行了"。
 *
 * <p>2026-11-05 实测踩到的就是这个坑：{@code CorsFilter} 当时被声明成普通 {@code @Bean}，
 * 单元测试 7 条全绿，但真实请求下合法 Origin 的预检返回 <b>HTTP 401 且一个
 * {@code Access-Control-Allow-*} 头都没有</b>——因为 Spring Security 的过滤器链
 * order = -100，排在普通 Bean（{@code LOWEST_PRECEDENCE}）之前，
 * 预检请求先被认证拦下，永远走不到 CORS 过滤器。
 *
 * <p>这类"测试绿、线上错"的缺陷，只有跨过容器边界才能发现。所以这个测试
 * <b>刻意不用 MockMvc</b>，用 {@code RANDOM_PORT} 起真容器 + 真 HTTP 客户端。
 *
 * <h3>断言选择</h3>
 *
 * <p>对陌生源不判状态码、只判"没有 ACAO 头"。因为浏览器判断跨域是否放行
 * <b>只看 ACAO 头</b>，哪怕服务端返回 403，只要没这个头浏览器就是拒绝的；
 * 反过来某些中间件配置差异也会让状态码不同，锁死状态码等于给未来埋假阳性。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:mediquery_cors;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-h2.sql",
        // 默认白名单就是本地开发地址，这里显式写一遍，避免将来默认值变了让测试失去意义
        "cors.allowed-origins=http://localhost:5173,http://127.0.0.1:5173"
})
class CorsPreflightIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    /**
     * 发一个标准的 CORS 预检请求。
     *
     * <p>浏览器发的预检一定带这三个头，缺任何一个服务端就不会当成预检处理，
     * 测出来的结果也就没有代表性。
     */
    private ResponseEntity<String> preflight(String url, String origin, String method) {
        var headers = new HttpHeaders();
        headers.set("Origin", origin);
        headers.set("Access-Control-Request-Method", method);
        headers.set("Access-Control-Request-Headers", "authorization,content-type");
        return rest.exchange(url, HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);
    }

    @Test
    @DisplayName("合法 Origin 的预检：200 + 回显 Origin + 允许凭证，且不会被 401 拦掉")
    void allowedOriginPreflightSucceeds() {
        var response = preflight("/api/v1/stats", "http://localhost:5173", "GET");

        assertEquals(200, response.getStatusCode().value(),
                "预检返回 " + response.getStatusCode().value() + " 说明请求没走到 CORS 就结束了。"
                        + "最常见原因是 CorsFilter 没挂在 Spring Security 链上"
                        + "（Security 的 order=-100，裸 Bean 是 LOWEST_PRECEDENCE，排在后面），"
                        + "于是 OPTIONS 先被认证拦成 401。");

        assertEquals("http://localhost:5173", response.getHeaders().getFirst("Access-Control-Allow-Origin"),
                "合法源必须被原样回显到 ACAO 头，浏览器只认这个头。");

        assertEquals("true", response.getHeaders().getFirst("Access-Control-Allow-Credentials"));

        assertNotNull(response.getHeaders().getFirst("Access-Control-Allow-Methods"),
                "预检响应必须告诉浏览器允许哪些方法，否则真实请求会被浏览器自己挡掉。");

        assertTrue(String.valueOf(response.getHeaders().getFirst("Access-Control-Allow-Methods")).contains("POST"),
                "Allow-Methods 里必须有 POST，否则前端无法提交任何表单。");
    }

    @Test
    @DisplayName("受保护接口的预检也必须放行——预检本身不带 Authorization 头")
    void preflightOfProtectedEndpointIsNotBlockedByAuth() {
        // /api/v1/user/me 需要登录，但浏览器发的预检不带 token。
        // 如果 security 在 CORS 之前判权限，这里就会 401，前端表现为"跨域被拦"。
        var response = preflight("/api/v1/user/me", "http://localhost:5173", "GET");

        assertEquals(200, response.getStatusCode().value(),
                "受保护接口的预检被拦了。CORS 处理必须排在认证之前。");
        assertEquals("http://localhost:5173", response.getHeaders().getFirst("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("非白名单 Origin 拿不到任何 CORS 许可头")
    void foreignOriginGetsNoCorsHeaders() {
        var response = preflight("/api/v1/stats", "https://evil.example.com", "GET");

        assertNull(response.getHeaders().getFirst("Access-Control-Allow-Origin"),
                "陌生源拿到了 ACAO 头 = 任意站点都能跨域读我们的响应。"
                        + "如果这条红了，检查是不是有人把 allowed-origins 配成了 *。");
        assertFalse("*".equals(response.getHeaders().getFirst("Access-Control-Allow-Origin")),
                "ACAO 绝不能是 *，尤其我们还开了 allowCredentials。");
    }

    @Test
    @DisplayName("127.0.0.1:5173 与 localhost:5173 都在默认白名单里")
    void bothLoopbackSpellingsAreAllowed() {
        // 这两个是不同字符串，前端用哪个取决于它怎么访问的，两个都得放行，
        // 否则本地开发时会出现"换个地址栏写法就跨域失败"。
        var byIp = preflight("/api/v1/stats", "http://127.0.0.1:5173", "GET");
        assertEquals("http://127.0.0.1:5173", byIp.getHeaders().getFirst("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("不带 Origin 头的普通请求不受影响")
    void requestWithoutOriginIsUnaffected() {
        // 同源请求、curl、服务端调用都不带 Origin，CORS 不该拦截它们。
        var response = rest.getForEntity("/api/v1/stats", String.class);

        assertEquals(200, response.getStatusCode().value(),
                "不带 Origin 的请求被 CORS 拦了，说明规则写得太宽或过滤器位置不对。");
        assertNull(response.getHeaders().getFirst("Access-Control-Allow-Origin"),
                "没有 Origin 时不该凭空返回 ACAO 头。");
    }
}
