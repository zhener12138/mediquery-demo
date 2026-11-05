package com.mediquery.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * JWT 工具类：生成accessToken和refreshToken、解析token、校验有效期
 *
 * <h3>密钥为什么必须在启动时校验</h3>
 *
 * <p>改之前 {@code application.yml} 写的是
 * {@code jwt.secret: ${JWT_SECRET:mediquery-jwt-secret-key-2026-must-be-at-least-256-bits-long}}——
 * 带一个写死在仓库里的默认值。这意味着<b>谁 clone 下来都能直接跑</b>，
 * 而这个默认值在 GitHub 上是公开的。
 *
 * <p>后果不是"配置不够优雅"，是很具体的：任何人拿这个公开密钥就能伪造出合法的
 * accessToken，带 {@code role: 1} 直接进管理后台。项目里所有接口的鉴权都建立在
 * "签名只有服务端能生成"这个前提上，密钥公开等于前提不成立。
 *
 * <p>现在的做法：
 * <ul>
 *   <li>{@code application.yml} 里只留 {@code ${JWT_SECRET:}}，没有兜底值；</li>
 *   <li>构造器里校验——空、太短、或者等于历史上那个泄露过的默认值，一律启动失败。</li>
 * </ul>
 *
 * <p>失败信息写清"怎么修"，因为这类问题通常在部署现场才炸，
 * 而看到报错的人往往不是写代码的人。
 */
@Component
public class JwtUtil {

    /**
     * HS256 要求密钥至少 256 位。jjwt 的 {@code Keys.hmacShaKeyFor} 也会校验，
     * 但它抛的是 {@code WeakKeyException}，信息里只有字节数，不知道该怎么改。
     */
    private static final int MIN_SECRET_BYTES = 32;

    /**
     * 曾经写在仓库里的默认密钥。即使有人把它配回环境变量，也要拦下来——
     * 因为它已经进入过公开的 git 历史，不能再当密钥用。
     */
    private static final String LEAKED_DEFAULT = "mediquery-jwt-secret-key-2026-must-be-at-least-256-bits-long";

    private final SecretKey key;
    private final long accessTokenExpiration;
    private final long refreshTokenExpiration;

    /**
     * token 类型声明。access 与 refresh 必须是两种东西——
     *
     * <p>改之前 refreshToken 只是「过期时间更长的 token」，两者结构完全相同，
     * 服务端没有任何办法分辨手里这个串是哪种。加上 typ 之后，
     * {@code /auth/refresh} 才能理直气壮地拒绝一个 accessToken：
     * 「你拿的是通行证，不是续期凭据」。
     */
    private static final String TYPE_CLAIM = "typ";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.access-token-expiration}") long accessTokenExpiration,
                   @Value("${jwt.refresh-token-expiration}") long refreshTokenExpiration) {
        validateSecret(secret);
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiration = accessTokenExpiration;
        this.refreshTokenExpiration = refreshTokenExpiration;
    }

    /**
     * 启动时校验密钥。故意写成 {@code static}，方便单独测。
     *
     * @throws IllegalStateException 密钥不可用时
     */
    static void validateSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT 密钥未配置，应用拒绝启动。\n"
                            + "设置方式：在 backend/.env 或环境变量里加一行\n"
                            + "  JWT_SECRET=<至少32个字符的随机字符串>\n"
                            + "生成一个：openssl rand -base64 48\n"
                            + "这里故意不提供默认值——默认值会进仓库，等于公开密钥，"
                            + "任何人都能伪造管理员 token。");
        }
        if (LEAKED_DEFAULT.equals(secret.trim())) {
            throw new IllegalStateException(
                    "JWT 密钥用的是旧版本提交在 git 历史里的默认值，即使配到环境变量也不算安全。\n"
                            + "这个值已经公开过，请换一个新的：openssl rand -base64 48");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT 密钥太短：" + secret.getBytes(StandardCharsets.UTF_8).length + " 字节，"
                            + "HS256 至少需要 " + MIN_SECRET_BYTES + " 字节（256 位）。\n"
                            + "生成一个：openssl rand -base64 48");
        }
    }

    // 生成accessToken（含userId+role，15分钟过期）
    public String generateAccessToken(Long userId, String username, Integer role) {
        return Jwts.builder()
                .claims(Map.of("userId", userId, "role", role, TYPE_CLAIM, TYPE_ACCESS))
                .subject(username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTokenExpiration))
                .signWith(key)
                .compact();
    }

    // 生成refreshToken（仅userId，7天过期）
    public String generateRefreshToken(Long userId, String username) {
        return Jwts.builder()
                .claims(Map.of("userId", userId, TYPE_CLAIM, TYPE_REFRESH))
                .subject(username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshTokenExpiration))
                .signWith(key)
                .compact();
    }

    /**
     * 这个串是不是 refreshToken。
     *
     * <p>用 {@code TYPE_REFRESH.equals(...)} 判断而不是 {@code !TYPE_ACCESS.equals(...)}：
     * 老版本签出来的 token 没有 typ 声明，两种都判 false 更安全——
     * 把它们当成「既不是 access 也不是 refresh」，让用户重新登录一次，
     * 好过让一个语义不明的串通过检查。
     */
    public boolean isRefreshToken(String token) {
        try {
            return TYPE_REFRESH.equals(parseToken(token).get(TYPE_CLAIM, String.class));
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /** 这个串是不是 accessToken。 */
    public boolean isAccessToken(String token) {
        try {
            return TYPE_ACCESS.equals(parseToken(token).get(TYPE_CLAIM, String.class));
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /** accessToken 的有效期（毫秒），供 {@code /auth/refresh} 回填 expiresIn，避免再硬编码一份。 */
    public long getAccessTokenExpiration() {
        return accessTokenExpiration;
    }

    // 解析token（验签+解码payload）
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    // 校验token是否合法（签名未篡改 + 未过期）
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    // 从token中提取userId
    public Long getUserId(String token) {
        return parseToken(token).get("userId", Long.class);
    }

    // 从token中提取用户名
    public String getUsername(String token) {
        return parseToken(token).getSubject();
    }
}
