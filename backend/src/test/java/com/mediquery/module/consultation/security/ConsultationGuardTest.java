package com.mediquery.module.consultation.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mediquery.module.consultation.entity.Consultation;
import com.mediquery.module.consultation.entity.ConsultationStatus;
import com.mediquery.module.consultation.entity.Severity;
import com.mediquery.module.consultation.entity.SummaryStatus;
import com.mediquery.module.consultation.mapper.ConsultationMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 归属校验的行为约束。
 *
 * <p>{@code ConsultationGuard} 是整套问诊单权限的守门人：它返回 true 的动作
 * 会被真正执行。所以这里测的不是「能不能取到 userId」，而是
 * <b>什么情况下必须返回 false</b> —— 那些才是数据泄露的入口。
 *
 * <p>用 {@code @SpringBootTest} 而不是纯 Mockito：{@code isOwner} 里用的是
 * {@code Wrappers.lambdaQuery()}，它需要 MyBatis-Plus 的 TableInfo 缓存，
 * 而那份缓存只有在实体被容器扫到之后才会建立。纯 Mockito 下会抛
 * {@code can not find lambda cache for this entity}。用起来会慢一点，
 * 但测的是真实的那条查询路径。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:mediquery_guard;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "jwt.secret=test-secret-key-must-be-at-least-256-bits-long-for-hs256",
        "mediquery.retrieval.strategy=like"
})
class ConsultationGuardTest {

    @Autowired
    private ConsultationMapper consultationMapper;

    @Autowired
    private ConsultationGuard guard;

    private Integer mine;
    private Integer others;

    @BeforeEach
    void setUp() {
        consultationMapper.delete(Wrappers.<Consultation>lambdaQuery().gt(Consultation::getId, 0));
        mine = insert(42, "guard-mine");
        others = insert(99, "guard-others");
    }

    @AfterEach
    void tearDown() {
        consultationMapper.delete(Wrappers.<Consultation>lambdaQuery().gt(Consultation::getId, 0));
    }

    private Integer insert(Integer patientId, String sessionId) {
        var c = new Consultation();
        c.setPatientId(patientId);
        c.setSessionId(sessionId);
        c.setChiefComplaint("guard test");
        c.setStatus(ConsultationStatus.IN_PROGRESS);
        c.setSeverity(Severity.LOW);
        c.setSummaryStatus(SummaryStatus.NONE);
        c.setLastActiveAt(LocalDateTime.now());
        c.setVersion(0);
        consultationMapper.insert(c);
        return c.getId();
    }

    private static Authentication authWith(Object details) {
        var auth = new UsernamePasswordAuthenticationToken(
                "someone", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        auth.setDetails(details);
        return auth;
    }

    @Test
    @DisplayName("本人：返回 true")
    void ownerIsAllowed() {
        assertThat(guard.isOwner(mine, authWith(42L))).isTrue();
    }

    @Test
    @DisplayName("他人：返回 false")
    void strangerIsRejected() {
        assertThat(guard.isOwner(others, authWith(42L))).isFalse();
    }

    @Test
    @DisplayName("单子不存在：也返回 false，且不抛异常")
    void missingConsultationIsRejected() {
        assertThat(guard.isOwner(999_999, authWith(42L))).isFalse();
    }

    @Test
    @DisplayName("匿名认证：直接 false")
    void anonymousIsRejected() {
        var anon = new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

        assertThat(guard.isOwner(mine, anon)).isFalse();
    }

    @Test
    @DisplayName("authentication 为 null：返回 false 而不是 NPE")
    void nullAuthenticationIsRejected() {
        assertThat(guard.isOwner(mine, null)).isFalse();
    }

    @Test
    @DisplayName("consultationId 为 null：返回 false，不查库")
    void nullIdIsRejected() {
        assertThat(guard.isOwner(null, authWith(42L))).isFalse();
    }

    @Test
    @DisplayName("未认证的 token（isAuthenticated=false）：返回 false")
    void unauthenticatedTokenIsRejected() {
        var auth = new UsernamePasswordAuthenticationToken("someone", null, List.of());

        assertThat(ConsultationGuard.currentUserId(auth)).isNull();
    }

    // ───── userId 的类型处理：这里曾经是真实的坑 ─────

    @Test
    @DisplayName("details 是 Long（JWT claim 反序列化的真实类型）：转成 int")
    void longDetailsAreConverted() {
        assertThat(ConsultationGuard.currentUserId(authWith(42L))).isEqualTo(42);
    }

    @Test
    @DisplayName("details 已经是 Integer：原样返回")
    void integerDetailsArePassedThrough() {
        assertThat(ConsultationGuard.currentUserId(authWith(42))).isEqualTo(42);
    }

    @Test
    @DisplayName("details 是字符串：返回 null，不强转")
    void stringDetailsYieldNull() {
        assertThat(ConsultationGuard.currentUserId(authWith("42"))).isNull();
    }

    @Test
    @DisplayName("details 为 null：返回 null")
    void nullDetailsYieldNull() {
        assertThat(ConsultationGuard.currentUserId(authWith(null))).isNull();
    }
}
