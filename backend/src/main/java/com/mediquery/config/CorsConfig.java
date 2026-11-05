package com.mediquery.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨域配置：只放行配置里明确列出的源。
 *
 * <h3>改了什么，为什么</h3>
 *
 * <p>原来是 {@code setAllowedOriginPatterns(List.of("*"))} + {@code setAllowCredentials(true)}。
 * 这两行放在一起是浏览器安全里的经典错误组合：
 *
 * <ul>
 *   <li>{@code "*"} 意味着<b>任何网站</b>的 JS 都能跨域读我们的响应；</li>
 *   <li>{@code allowCredentials} 意味着浏览器会带上 Cookie。<b>再加上通配</b>，
 *       等于允许任意站点以用户身份读数据。</li>
 * </ul>
 *
 * <p>浏览器规范其实禁止 {@code Access-Control-Allow-Origin: *} 与凭证同时生效，
 * 所以 Spring 的 {@code allowedOriginPatterns("*")} 会回显具体 Origin 绕过这条限制——
 * 看起来"能跑"，实际上把限制也一起绕过去了。
 *
 * <h3>为什么本项目风险没那么极端，但仍然要改</h3>
 *
 * <p>我们的认证走 {@code Authorization: Bearer} 头而不是 Cookie，浏览器不会自动携带，
 * 所以攻击者拿不到用户的 token，危害比 Cookie 场景小。但：
 *
 * <ul>
 *   <li>一旦将来有人加了 Cookie 会话或把 token 存进 Cookie，这行配置立刻变成真漏洞，
 *       而那时候没人会想起来回来看这里；</li>
 *   <li>{@code "*"} 让任何站点都能在前端做接口探测（哪些接口存在、报什么错），
 *       这些信息本身不该免费给出去。</li>
 * </ul>
 *
 * <p>所以改成白名单：默认只放行本地开发地址，生产靠 {@code CORS_ALLOWED_ORIGINS} 环境变量注入。
 *
 * <h3>怎么配</h3>
 *
 * <p>{@code application.yml} 里：
 * <pre>
 * cors:
 *   allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:5173,http://127.0.0.1:5173}
 * </pre>
 *
 * <p>多个源用英文逗号分隔。支持通配子域（如 {@code https://*.example.com}），
 * 因为 Spring 的 {@code setAllowedOriginPatterns} 本来就支持——但<b>不要只写一个 {@code *}</b>，
 * 那等于没改。
 */
@Configuration
public class CorsConfig {

    /**
     * 允许的源列表，逗号分隔。
     *
     * <p>默认值只覆盖本地开发。Docker Compose 里前端和后端同源（都走 80 端口的 nginx 反代），
     * 根本不触发跨域，所以默认值够用；真需要跨域部署时用环境变量覆盖。
     */
    private final List<String> allowedOrigins;

    public CorsConfig(@Value("${cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}") String rawOrigins) {
        this.allowedOrigins = parse(rawOrigins);
    }

    /**
     * CORS 规则的唯一来源，交给 Spring Security 的 {@code .cors()} 使用。
     *
     * <h3>为什么不是直接注册 {@link CorsFilter} Bean</h3>
     *
     * <p>这里踩过一个坑：原来把 {@code CorsFilter} 声明成普通 {@code @Bean}，
     * 看似"配置了跨域"，实际预检请求（{@code OPTIONS}）全部被 Spring Security 先拦下返回 401，
     * 响应里一个 {@code Access-Control-Allow-*} 头都没有。
     *
     * <p>原因是 Servlet 过滤器与 Security 过滤器链的执行顺序：Security 的
     * {@code springSecurityFilterChain} 是 order = -100，而一个没标 {@code @Order} 的普通 Bean
     * 是 {@code LOWEST_PRECEDENCE}，排在 Security 之后。请求先被 Security 判成未认证 → 401，
     * 根本走不到我们的 CORS 过滤器。而单元测试是直接调 {@code corsFilter().doFilter(...)}，
     * 绕过了容器，所以测试全绿、线上全错。
     *
     * <p>正确做法是让 Security 自己接管：{@code http.cors(c -> c.configurationSource(source))}
     * 会把 CORS 处理插到过滤器链最前面（在认证之前），预检请求直接返回 200 而不进 authorization。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", buildConfiguration());
        return source;
    }

    /**
     * 供单元测试直接驱动的过滤器。
     *
     * <p><b>不是</b> {@code @Bean}——注册成 Bean 反而会被放到 Security 之后，见上面那段说明。
     * 生产链路由 {@link #corsConfigurationSource()} 经 Spring Security 生效。
     */
    CorsFilter corsFilter() {
        return new CorsFilter(corsConfigurationSource());
    }

    private CorsConfiguration buildConfiguration() {
        var config = new CorsConfiguration();

        // 用 setAllowedOriginPatterns 而不是 setAllowedOrigins：
        // 前者支持 https://*.example.com 这种子域通配，后者不支持。
        // 注意两者都不该出现裸的 "*"。
        config.setAllowedOriginPatterns(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Requested-With"));
        config.setExposedHeaders(List.of("Content-Disposition", "traceId"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        return config;
    }

    /**
     * 解析逗号分隔的源列表。
     *
     * <p>顺手做了三件防呆：
     * <ol>
     *   <li>去掉首尾空格（环境变量里写成 {@code "a, b"} 很常见）；</li>
     *   <li>丢掉空串（尾随逗号会产生一个空项，Spring 会当成"允许空源"）；</li>
     *   <li>去掉结尾的斜杠——{@code http://a.com/} 和 {@code http://a.com} 在 Origin 头里
     *       是不同字符串，配置多写一个斜杠就会静默不匹配，排查起来很费时间。</li>
     * </ol>
     *
     * @throws IllegalStateException 解析后为空时直接启动失败
     */
    static List<String> parse(String rawOrigins) {
        var result = new ArrayList<String>();
        if (rawOrigins != null) {
            for (String piece : rawOrigins.split(",")) {
                String origin = piece.trim();
                while (origin.endsWith("/")) {
                    origin = origin.substring(0, origin.length() - 1);
                }
                if (!origin.isEmpty()) {
                    result.add(origin);
                }
            }
        }
        if (result.isEmpty()) {
            throw new IllegalStateException(
                    "cors.allowed-origins 解析后为空。要么在 application.yml 里给默认值，"
                            + "要么用环境变量 CORS_ALLOWED_ORIGINS 提供至少一个源。"
                            + "空列表会让所有跨域请求被拒——这比放开通配安全，但如果你确实需要跨域，"
                            + "现在就得配。");
        }
        return result;
    }

    /** 供测试读取，避免测试去反射 {@code @Value} 注入的字段。 */
    List<String> getAllowedOrigins() {
        return allowedOrigins;
    }
}
