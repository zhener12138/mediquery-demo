package com.mediquery.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtUtil 纯单元测试：不启动容器，几毫秒跑完。
 *
 * <h3>为什么先测这个类</h3>
 *
 * <p>JwtUtil 是整个鉴权链路的根。它错了，后面所有 {@code @PreAuthorize}、
 * 所有"只能看自己的数据"的判断全部失效，而且失效方式是静默的——不会报错，
 * 只会放行。这类组件必须有不依赖环境的测试兜着。
 *
 * <p>另一个理由：它是全项目少数几个"有真正逻辑"的类（签名、过期、claims 提取），
 * 剩下大部分 Service 是一行委托给 Mapper。测试预算有限时，先测有逻辑的。
 *
 * <h3>覆盖什么</h3>
 *
 * <ul>
 *   <li>签发的 token 能被自己解析回来，claims 不丢不串；</li>
 *   <li>accessToken 带 role，refreshToken 不带——这个差异是 security 的边界，不能被"统一一下"改掉；</li>
 *   <li>篡改签名后校验必须失败（不是抛异常吞掉，是返回 false）；</li>
 *   <li>过期 token 必须判为非法；</li>
 *   <li>拿别的密钥签的 token 必须判为非法。</li>
 * </ul>
 */
class JwtUtilTest {

    /** 32 字节以上，HS256 要求密钥不短于 256 bit */
    private static final String SECRET = "test-secret-key-must-be-at-least-256-bits-long-for-hs256";
    private static final String OTHER_SECRET = "another-secret-key-also-at-least-256-bits-long-xxxxxx";

    private static final long ACCESS_TTL = 15 * 60 * 1000L;      // 15 分钟
    private static final long REFRESH_TTL = 7 * 24 * 3600 * 1000L; // 7 天

    private static JwtUtil newJwtUtil(String secret, long accessTtl, long refreshTtl) {
        return new JwtUtil(secret, accessTtl, refreshTtl);
    }

    private static JwtUtil defaultJwtUtil() {
        return newJwtUtil(SECRET, ACCESS_TTL, REFRESH_TTL);
    }

    // ------------------------------------------------------------------
    // 正常路径
    // ------------------------------------------------------------------

    @Test
    void accessTokenRoundTrip() {
        JwtUtil jwt = defaultJwtUtil();

        String token = jwt.generateAccessToken(42L, "zhangsan", 1);

        assertTrue(jwt.validateToken(token));
        assertEquals(42L, jwt.getUserId(token));
        assertEquals("zhangsan", jwt.getUsername(token));

        Claims claims = jwt.parseToken(token);
        assertEquals(1, ((Number) claims.get("role")).intValue());
    }

    @Test
    void accessTokenCarriesRoleButRefreshTokenDoesNot() {
        JwtUtil jwt = defaultJwtUtil();

        String access = jwt.generateAccessToken(7L, "lisi", 0);
        String refresh = jwt.generateRefreshToken(7L, "lisi");

        assertEquals(0, ((Number) jwt.parseToken(access).get("role")).intValue());

        // refreshToken 只带 userId，不带 role —— 这是刻意的：refresh 只用于换新 token，
        // 不应该携带任何授权信息。如果这里挂了，说明有人"顺手统一"了签发逻辑。
        assertNull(jwt.parseToken(refresh).get("role"),
                "refreshToken 不应携带 role，否则等于给了它一次授权能力");
        assertEquals(7L, jwt.getUserId(refresh));
        assertEquals("lisi", jwt.getUsername(refresh));
    }

    @Test
    void refreshTokenHasLongerExpiryThanAccessToken() {
        JwtUtil jwt = defaultJwtUtil();

        long now = System.currentTimeMillis();
        long accessExp = jwt.parseToken(jwt.generateAccessToken(1L, "u", 0)).getExpiration().getTime();
        long refreshExp = jwt.parseToken(jwt.generateRefreshToken(1L, "u")).getExpiration().getTime();

        // 只要相对关系成立即可，不卡死绝对时间（避免机器慢导致偶发失败）
        assertTrue(refreshExp > accessExp, "refreshToken 的过期时间必须晚于 accessToken");
        assertTrue(accessExp > now);
    }

    // ------------------------------------------------------------------
    // 非法输入
    // ------------------------------------------------------------------

    @Test
    void tamperedTokenIsRejected() {
        JwtUtil jwt = defaultJwtUtil();
        String token = jwt.generateAccessToken(9L, "wangwu", 0);

        // 翻掉签名的最后一个字符
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertFalse(jwt.validateToken(tampered), "签名被改过的 token 必须校验失败");
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        JwtUtil issuer = newJwtUtil(OTHER_SECRET, ACCESS_TTL, REFRESH_TTL);
        JwtUtil verifier = defaultJwtUtil();

        String foreign = issuer.generateAccessToken(1L, "attacker", 1);

        assertFalse(verifier.validateToken(foreign), "用别的密钥签的 token 必须校验失败");
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        // 用反射造一个 TTL 为负的实例：签出来的 token 一出生就过期。
        // 这样不用 sleep 就能测过期路径。
        Constructor<JwtUtil> ctor = JwtUtil.class.getConstructor(String.class, long.class, long.class);
        JwtUtil expiredIssuer = ctor.newInstance(SECRET, -1000L, -1000L);

        String expired = expiredIssuer.generateAccessToken(1L, "old", 0);

        JwtUtil jwt = defaultJwtUtil();
        assertFalse(jwt.validateToken(expired), "已过期的 token 必须校验失败");

        // 解析过期 token 应该抛 ExpiredJwtException，而不是返回 null 或空对象
        assertThrows(ExpiredJwtException.class, () -> jwt.parseToken(expired));
    }

    @Test
    void garbageInputIsRejectedNotThrown() {
        JwtUtil jwt = defaultJwtUtil();

        assertFalse(jwt.validateToken(""));
        assertFalse(jwt.validateToken("not-a-jwt"));
        assertFalse(jwt.validateToken("a.b.c"));
        assertFalse(jwt.validateToken(null));
    }
}
