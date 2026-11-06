package com.mediquery.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Actuator 探针的集成测试。
 *
 * <p>为什么用 {@code RANDOM_PORT} 而不是 MockMvc：这次要验的恰恰是
 * 「Security 放行规则 + Actuator 端点 + 序列化」三者串起来的结果。
 * MockMvc 会跳过 Servlet 容器的一部分装配，之前 CORS 就是在单测里全绿、
 * 在真容器里全错（见 {@code CorsConfig} 的 javadoc）。探针是给容器编排用的，
 * 必须按容器的方式验。
 *
 * <p>断言分两类，第二类才是真正有价值的：
 * <ul>
 *   <li>格式：返回 200、body 里有 status 字段、detail 不外泄</li>
 *   <li><b>语义</b>：能不能发现故障。一个永远返回 UP 的探针等于没探。
 *       所以本类注册了一个可切换的 {@link HealthIndicator}，把健康状态打成 DOWN 时，
 *       端点必须真的返回 503 —— 只断言「现在是 UP」只能证明它现在能返回 200，
 *       证明不了它会在该红的时候红。</li>
 * </ul>
 *
 * <p>用原始类型 {@code Map} 而不是 {@code Map<String, Object>}：
 * {@code TestRestTemplate.getForEntity(url, Map.class)} 的返回类型就是
 * {@code ResponseEntity<Map>}，写成具体泛型参数会编译失败。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:mediquery_actuator;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "jwt.secret=test-secret-key-must-be-at-least-256-bits-long-for-hs256",
        "management.endpoint.health.show-details=never"
})
@SuppressWarnings({"rawtypes", "unchecked"})
class ActuatorHealthTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ToggleableHealth toggleableHealth;

    /**
     * 一个测试专用的健康指标，用开关控制它报 UP 还是 DOWN。
     *
     * <p>它参与 HealthEndpoint 的聚合：Spring Boot 的默认策略是
     * 「任何一个 indicator 报 DOWN，整体就 DOWN」，并且 HTTP 状态码会从 200 变成 503。
     * 有了这个开关，就能在同一套已部署的配置上（不是特制的测试配置）
     * 验证「探针会不会变红」。
     */
    @TestConfiguration
    static class ToggleableHealthConfig {
        @Bean
        ToggleableHealth toggleableHealth() {
            return new ToggleableHealth();
        }
    }

    static class ToggleableHealth implements HealthIndicator {
        private final AtomicBoolean healthy = new AtomicBoolean(true);

        void setHealthy(boolean value) {
            healthy.set(value);
        }

        @Override
        public Health health() {
            return healthy.get()
                    ? Health.up().build()
                    : Health.down().withDetail("reason", "simulated outage").build();
        }
    }

    @Test
    @DisplayName("GET /actuator/health 不需要认证，返回 200 UP")
    void healthIsPublicAndUp() {
        toggleableHealth.setHealthy(true);

        ResponseEntity<Map> resp = rest.getForEntity("/actuator/health", Map.class);

        // 401 说明 Security 的放行规则没生效 —— 这正是容器 healthcheck 会踩的坑
        assertThat(resp.getStatusCode())
                .as("探针必须免认证；401 意味着 Docker healthcheck 永远 unhealthy")
                .isEqualTo(HttpStatus.OK);

        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("status"))
                .as("health 必须有一个 status 字段，容器编排读的是它")
                .isEqualTo("UP");
    }

    @Test
    @DisplayName("依赖故障时探针必须变红（503 + DOWN）")
    void healthTurnsRedWhenDependencyFails() {
        try {
            toggleableHealth.setHealthy(false);

            ResponseEntity<Map> resp = rest.getForEntity("/actuator/health", Map.class);

            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().get("status"))
                    .as("有 indicator 报 DOWN 时聚合结果必须是 DOWN")
                    .isEqualTo("DOWN");
            assertThat(resp.getStatusCode())
                    .as("DOWN 必须映射成 503，否则 healthcheck 只看 HTTP 码时会判成健康")
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        } finally {
            // 恢复，避免影响同类里的其他用例（它们共享同一个 Spring 上下文）
            toggleableHealth.setHealthy(true);
        }
    }

    @Test
    @DisplayName("health 不泄露内部细节（show-details=never 生效）")
    void healthDoesNotLeakDetails() {
        toggleableHealth.setHealthy(true);

        ResponseEntity<Map> resp = rest.getForEntity("/actuator/health", Map.class);
        Map body = resp.getBody();

        assertThat(body).isNotNull();
        // 有 components 才可能带 db/redis 的地址、版本、错误信息
        assertThat(body).doesNotContainKey("components");

        String raw = String.valueOf(body).toLowerCase();
        assertThat(raw)
                .as("health 响应里不应该出现数据库连接串、密码、驱动类名")
                .doesNotContain("jdbc:")
                .doesNotContain("password")
                .doesNotContain("h2");
    }

    @Test
    @DisplayName("GET /actuator/info 免认证可访问")
    void infoIsPublic() {
        ResponseEntity<String> resp = rest.getForEntity("/actuator/info", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("未暴露的 actuator 端点对外不可见")
    void disabledEndpointsAreNotReachable() {
        // env/beans/metrics 都没在 exposure.include 里。
        // 不写死具体状态码，是因为不同 Spring Boot 版本对「未暴露端点」的处理不同
        // （有返回 404 的、有返回 401 的）。这里真正要守的是「拿不到数据」。
        for (String endpoint : new String[]{"/actuator/env", "/actuator/beans", "/actuator/metrics"}) {
            ResponseEntity<String> resp = rest.getForEntity(endpoint, String.class);
            assertThat(resp.getStatusCode().value())
                    .as("%s 不应该可访问；拿到 200 说明 exposure.include 被改宽了", endpoint)
                    .isNotEqualTo(200);
        }
    }
}
