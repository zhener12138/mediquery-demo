package com.mediquery.module.auth.service;

import com.mediquery.common.exception.BusinessException;
import com.mediquery.module.auth.dto.LoginRequest;
import com.mediquery.module.user.entity.User;
import com.mediquery.module.user.mapper.UserMapper;
import com.mediquery.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 登录防爆破 + refreshToken 续期。
 *
 * <p>为什么用 Mockito 而不是起 Spring 上下文：这两块逻辑的核心是
 * 「Redis 里那个计数器现在是多少」和「这个串能不能换出新 token」，
 * 用真 Redis 反而会让测试依赖环境（CI 上没有 Redis 服务）。
 * 数据库那侧用的是 {@link UserMapper} 接口，mock 掉即可。
 *
 * <p>注意这里刻意<b>不</b>去测「密码用 BCrypt 加密」——那是框架的行为，
 * 测它等于测 Spring Security 有没有 bug。要测的是我们自己写的那几行判断。
 */
class AuthServiceLoginTest {

    private static final String SECRET = "test-secret-key-must-be-at-least-256-bits-long-for-hs256";
    private static final long ACCESS_TTL = 900_000L;
    private static final long REFRESH_TTL = 604_800_000L;

    private UserMapper userMapper;
    private RedisTemplate<String, Object> redisTemplate;
    private ValueOperations<String, Object> valueOps;
    private JwtUtil jwtUtil;
    private AuthService authService;
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userMapper = mock(UserMapper.class);
        redisTemplate = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);

        jwtUtil = new JwtUtil(SECRET, ACCESS_TTL, REFRESH_TTL);
        passwordEncoder = new BCryptPasswordEncoder();
        authService = new AuthService(userMapper, passwordEncoder, jwtUtil, null, redisTemplate);
    }

    private User enabledUser() {
        User u = new User();
        u.setId(7);
        u.setUsername("tester");
        u.setPassword(passwordEncoder.encode("correct-password"));
        u.setRealName("测试用户");
        u.setRoleStatus(0);
        u.setStatus(1);
        return u;
    }

    private LoginRequest login(String username, String password) {
        LoginRequest r = new LoginRequest();
        r.setUsername(username);
        r.setPassword(password);
        return r;
    }

    // ---------------------------------------------------------------- 防爆破

    @Test
    @DisplayName("密码错一次：计数 +1，且第一次失败会设置过期时间")
    void firstFailureStartsCounter() {
        when(userMapper.selectOne(any())).thenReturn(enabledUser());
        when(valueOps.increment("login:fail:tester")).thenReturn(1L);
        when(valueOps.get("login:fail:tester")).thenReturn(null);

        assertThrows(BusinessException.class, () -> authService.login(login("tester", "wrong")));

        verify(valueOps).increment("login:fail:tester");
        verify(redisTemplate).expire(eq("login:fail:tester"), eq(15L), any(TimeUnit.class));
    }

    @Test
    @DisplayName("第二次失败不再重置过期时间——否则一直错就永远不过期")
    void subsequentFailuresDoNotResetTtl() {
        when(userMapper.selectOne(any())).thenReturn(enabledUser());
        when(valueOps.increment("login:fail:tester")).thenReturn(2L);
        when(valueOps.get("login:fail:tester")).thenReturn(null);

        assertThrows(BusinessException.class, () -> authService.login(login("tester", "wrong")));

        verify(redisTemplate, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("已满 5 次直接拒绝，且不再查库、不再验密")
    void lockedAccountIsRejectedWithoutTouchingDatabase() {
        when(valueOps.get("login:fail:tester")).thenReturn(5);

        BusinessException e = assertThrows(BusinessException.class,
                () -> authService.login(login("tester", "correct-password")));

        assertEquals(403, e.getCode());
        assertTrue(e.getMessage().contains("尝试次数过多"), "实际消息：" + e.getMessage());
        verify(userMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("锁定提示带剩余分钟数，且向上取整")
    void lockMessageShowsRemainingMinutes() {
        when(valueOps.get("login:fail:tester")).thenReturn(9);
        when(redisTemplate.getExpire("login:fail:tester", TimeUnit.SECONDS)).thenReturn(61L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> authService.login(login("tester", "whatever")));

        // 61 秒 = 2 分钟（向上取整），写 1 分钟会让用户等到 1 分钟时还是进不去
        assertTrue(e.getMessage().contains("2 分钟"), "实际消息：" + e.getMessage());
    }

    @Test
    @DisplayName("用户名不存在也计数——否则爆破脚本能顺带探出哪些账号存在")
    void unknownUserStillCountsAsFailure() {
        when(userMapper.selectOne(any())).thenReturn(null);
        when(valueOps.increment("login:fail:ghost")).thenReturn(1L);
        when(valueOps.get("login:fail:ghost")).thenReturn(null);

        assertThrows(BusinessException.class, () -> authService.login(login("ghost", "x")));

        verify(valueOps).increment("login:fail:ghost");
    }

    @Test
    @DisplayName("登录成功清零计数，避免历史失败拖累下次登录")
    void successfulLoginClearsCounter() {
        when(userMapper.selectOne(any())).thenReturn(enabledUser());
        when(valueOps.get("login:fail:tester")).thenReturn(3);

        var res = authService.login(login("tester", "correct-password"));

        assertNotNull(res.getAccessToken());
        verify(redisTemplate).delete("login:fail:tester");
    }

    @Test
    @DisplayName("被禁用的账号返回 403 且不计数——它本来就不该能登录")
    void disabledAccountIsForbiddenAndNotCounted() {
        User disabled = enabledUser();
        disabled.setStatus(0);
        when(userMapper.selectOne(any())).thenReturn(disabled);
        when(valueOps.get("login:fail:tester")).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> authService.login(login("tester", "correct-password")));

        assertEquals(403, e.getCode());
        verify(valueOps, never()).increment(anyString());
    }

    // ---------------------------------------------------------------- 续期

    @Test
    @DisplayName("合法 refreshToken 能换出一对新的 token")
    void validRefreshTokenIssuesNewPair() {
        when(valueOps.get("login:fail:tester")).thenReturn(null);
        when(userMapper.selectOne(any())).thenReturn(enabledUser());
        var loginRes = authService.login(login("tester", "correct-password"));

        when(userMapper.selectById(7)).thenReturn(enabledUser());
        var refreshed = authService.refresh(loginRes.getRefreshToken());

        assertNotNull(refreshed.getAccessToken());
        assertNotNull(refreshed.getRefreshToken());
        assertTrue(jwtUtil.isAccessToken(refreshed.getAccessToken()));
        assertTrue(jwtUtil.isRefreshToken(refreshed.getRefreshToken()));
        assertEquals(900L, refreshed.getExpiresIn(), "expiresIn 应为 900 秒，来自 JwtUtil 而非硬编码");
    }

    @Test
    @DisplayName("拿 accessToken 当 refreshToken 用必须被拒绝")
    void accessTokenCannotBeUsedToRefresh() {
        when(valueOps.get("login:fail:tester")).thenReturn(null);
        when(userMapper.selectOne(any())).thenReturn(enabledUser());
        var loginRes = authService.login(login("tester", "correct-password"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> authService.refresh(loginRes.getAccessToken()));

        assertEquals(401, e.getCode());
    }

    @Test
    @DisplayName("续期时用户已被禁用 → 403；已删除 → 401。不能只信 token 里的 userId")
    void refreshRechecksUserState() {
        when(valueOps.get("login:fail:tester")).thenReturn(null);
        when(userMapper.selectOne(any())).thenReturn(enabledUser());
        var loginRes = authService.login(login("tester", "correct-password"));
        String rt = loginRes.getRefreshToken();

        User disabled = enabledUser();
        disabled.setStatus(0);
        when(userMapper.selectById(7)).thenReturn(disabled);
        assertEquals(403, assertThrows(BusinessException.class, () -> authService.refresh(rt)).getCode());

        when(userMapper.selectById(7)).thenReturn(null);
        assertEquals(401, assertThrows(BusinessException.class, () -> authService.refresh(rt)).getCode());
    }

    @Test
    @DisplayName("空 / 乱码 / 用别的密钥签的 token 一律 401，不能抛 500")
    void garbageIsRejectedAsUnauthorized() {
        assertEquals(401, assertThrows(BusinessException.class, () -> authService.refresh(null)).getCode());
        assertEquals(401, assertThrows(BusinessException.class, () -> authService.refresh("   ")).getCode());
        assertEquals(401, assertThrows(BusinessException.class, () -> authService.refresh("not-a-jwt")).getCode());

        JwtUtil other = new JwtUtil("another-secret-key-that-is-also-32-bytes-long!!", ACCESS_TTL, REFRESH_TTL);
        String foreign = other.generateRefreshToken(7L, "tester");
        // 签名不匹配 → isRefreshToken 返回 false → 401（不是 500）
        assertEquals(401, assertThrows(BusinessException.class, () -> authService.refresh(foreign)).getCode());
    }

    @Test
    @DisplayName("两种 token 的 typ 不同：这是 refresh 端点能区分它们的前提")
    void accessAndRefreshCarryDifferentTypeClaims() {
        String access = jwtUtil.generateAccessToken(1L, "u", 0);
        String refresh = jwtUtil.generateRefreshToken(1L, "u");

        assertTrue(jwtUtil.isAccessToken(access));
        assertFalse(jwtUtil.isRefreshToken(access));
        assertTrue(jwtUtil.isRefreshToken(refresh));
        assertFalse(jwtUtil.isAccessToken(refresh));
    }

    @Test
    @DisplayName("没有 typ 声明的老 token 两种都不是——让它重新登录，而不是猜它是什么")
    void legacyTokenWithoutTypeClaimIsNeither() {
        // 用同一个密钥手工构造一个不带 typ 的 token，模拟阶段二之前签发的
        String legacy = io.jsonwebtoken.Jwts.builder()
                .claim("userId", 1L)
                .subject("u")
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 60_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();

        assertFalse(jwtUtil.isAccessToken(legacy));
        assertFalse(jwtUtil.isRefreshToken(legacy));
    }
}
