package com.mediquery.config;

import com.mediquery.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import jakarta.servlet.DispatcherType;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.MediaType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Spring Security 核心配置：JWT无状态认证 + RBAC角色权限控制
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Allow async dispatches (SSE streaming) to bypass re-authorization
                .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                // Public endpoints
                .requestMatchers("/api/v1/auth/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/illnesses/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/medicines/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/doctors/**").permitAll()
                .requestMatchers("/api/v1/files/upload").authenticated()
                .requestMatchers("/api/v1/feedbacks").authenticated()
                // Swagger/Knife4j
                .requestMatchers("/doc.html", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/webjars/**", "/swagger-resources/**").permitAll()
                // Admin only
                .requestMatchers(HttpMethod.POST, "/api/v1/illnesses/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/v1/illnesses/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/illnesses/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/medicines/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/v1/medicines/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/medicines/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/doctors/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/v1/doctors/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/doctors/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/dashboard/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                // Everything else requires authentication
                .anyRequest().authenticated()
            )
            // 未认证 → 401（默认实现返回 403，前端靠 401 判断跳登录）
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(authenticationEntryPoint())
                .accessDeniedHandler(accessDeniedHandler())
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 未携带/无效凭证访问受保护资源：401。
     * Spring Security 默认的 Http403ForbiddenEntryPoint 会返回 403，
     * 前端 axios 拦截器只对 401 做「清除 token + 跳登录」，因此必须显式返回 401。
     */
    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (HttpServletRequest request, HttpServletResponse response,
                org.springframework.security.core.AuthenticationException authException) ->
                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                        "{\"code\":401,\"message\":\"请先登录\",\"data\":null}");
    }

    /** 已登录但权限不足：403 */
    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (HttpServletRequest request, HttpServletResponse response,
                org.springframework.security.access.AccessDeniedException e) ->
                writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                        "{\"code\":403,\"message\":\"权限不足\",\"data\":null}");
    }

    private static void writeJson(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
