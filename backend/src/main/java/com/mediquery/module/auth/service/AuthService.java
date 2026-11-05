package com.mediquery.module.auth.service;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mediquery.common.constant.MedicalConstants;
import com.mediquery.common.exception.BusinessException;
import com.mediquery.integration.email.EmailClient;
import com.mediquery.module.auth.dto.LoginRequest;
import com.mediquery.module.auth.dto.RegisterRequest;
import com.mediquery.module.auth.dto.SendCodeRequest;
import com.mediquery.module.auth.dto.TokenResponse;
import com.mediquery.module.user.entity.User;
import com.mediquery.module.user.mapper.UserMapper;
import com.mediquery.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 认证服务：登录、注册(JWT双Token)、邮箱验证码(Redis TTL + 60s频率限制)、token 续期、登录防爆破。
 *
 * <h3>这一版改了什么</h3>
 *
 * <p>阶段二之前这里有个「说了没做」的地方：{@code login} 签发了 refreshToken，
 * 响应里也带上了，但<b>从来没有端点消费它</b>。前端 {@code stores/auth.ts} 拿到
 * 之后直接丢进 localStorage 再没人看。结果是 refreshToken 存在的唯一效果
 * 就是让响应体大了一点。
 *
 * <p>现在补上 {@link #refresh(String)}。顺带解决一个安全细节：refreshToken
 * 如果没有类型声明，它和 accessToken 在服务端眼里是同一种东西，
 * 意味着一个 7 天有效期的 refreshToken 可以直接当 15 分钟的 accessToken 用，
 * 等于把会话有效期悄悄延长了 672 倍。{@code JwtUtil} 新增了 {@code typ} 声明，
 * 这里用 {@code isRefreshToken()} 严格区分。
 *
 * <p>另加登录失败计数（{@link #LOGIN_FAIL_PREFIX}）：改之前 {@code /auth/login}
 * 没有任何限流，一个脚本每秒能试几十个密码。验证码接口有 60 秒限制，
 * 而真正的账号密码入口没有——这是最容易被忽视的一处。
 */
@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final EmailClient emailClient;
    private final RedisTemplate<String, Object> redisTemplate;

    public AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtUtil jwtUtil,
                       EmailClient emailClient, RedisTemplate<String, Object> redisTemplate) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.emailClient = emailClient;
        this.redisTemplate = redisTemplate;
    }

    private static final String CODE_PREFIX = "email:code:";
    private static final String CODE_RATE_PREFIX = "email:rate:";
    private static final int CODE_EXPIRE_MINUTES = 5;

    /**
     * 登录失败计数 key 前缀。
     *
     * <p>按用户名计数而不是按 IP：项目没有反向代理，{@code request.getRemoteAddr()}
     * 拿到的就是真实来源，但换 IP 的成本远低于换用户名，而攻击者真正想猜的是
     * 某个特定账号的密码。两者都有盲区，先防住「拿字典轮一个账号」这一类。
     */
    private static final String LOGIN_FAIL_PREFIX = "login:fail:";

    /** 连续失败几次开始锁定。5 次是常见运维口径，够低能挡住脚本，够高不至于让人打错三次就被锁。 */
    private static final int MAX_LOGIN_FAILURES = 5;

    /** 锁定时长。 */
    private static final int LOGIN_LOCK_MINUTES = 15;

    // 用户登录：防爆破检查 → 校验账号状态 → BCrypt验密 → 生成双Token
    public TokenResponse login(LoginRequest request) {
        String failKey = LOGIN_FAIL_PREFIX + request.getUsername();
        assertNotLocked(failKey);

        var user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, request.getUsername()));
        if (user == null) {
            // 用户不存在也要计数，否则「哪些用户名存在」这件事会被爆破脚本顺带探出来
            recordLoginFailure(failKey);
            throw BusinessException.unauthorized("用户名或密码错误");
        }
        if (user.getStatus() == 0) {
            throw BusinessException.forbidden("账号已被禁用");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            recordLoginFailure(failKey);
            throw BusinessException.unauthorized("用户名或密码错误");
        }

        // 登录成功清零，避免「白天输错 4 次，晚上正常登录一次，凌晨又被第四次输错锁住」
        redisTemplate.delete(failKey);
        return issueTokens(user);
    }

    /**
     * 用 refreshToken 换一对新的 token。
     *
     * <p>为什么换出来的是<b>一对</b>而不是只换 accessToken：
     * 既然已经验证过「这个人确实持有合法 refreshToken」，顺手续一个新的
     * refreshToken 可以让活跃用户的会话无限滚动（每 7 天续一次），
     * 而长期不用的账号自然过期。这是常见做法，也省掉一个「refreshToken 该不该轮换」的争论。
     *
     * @param refreshToken 客户端持有的 refreshToken
     * @return 新的 accessToken + refreshToken + userInfo
     * @throws BusinessException 401 当 token 不是 refreshToken、已过期、或用户已被禁用
     */
    public TokenResponse refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw BusinessException.unauthorized("缺少 refreshToken");
        }
        // 先判类型再解析 payload：isRefreshToken 内部已经吞掉了签名/过期异常，
        // 这里分开判是为了给出「拿错了 token 类型」和「token 已失效」两种不同提示
        if (!jwtUtil.isRefreshToken(refreshToken)) {
            throw BusinessException.unauthorized("refreshToken 无效或已过期，请重新登录");
        }

        Long userId;
        try {
            userId = jwtUtil.getUserId(refreshToken);
        } catch (RuntimeException e) {
            // isRefreshToken 刚解析成功过，正常不会走到这里；万一走到，当失效处理
            throw BusinessException.unauthorized("refreshToken 无效或已过期，请重新登录");
        }
        if (userId == null) {
            throw BusinessException.unauthorized("refreshToken 无效或已过期，请重新登录");
        }

        // 用户可能在这 7 天里被管理员禁用或删除，续期时必须重新查一次库。
        // 只信 token 里的 userId 就签发新 token，等于「一旦登录过就再也不会被踢下线」。
        var user = userMapper.selectById(userId.intValue());
        if (user == null) {
            throw BusinessException.unauthorized("账号不存在，请重新登录");
        }
        if (user.getStatus() == 0) {
            throw BusinessException.forbidden("账号已被禁用");
        }
        return issueTokens(user);
    }

    /** 签发 access + refresh 并组装响应。login 与 refresh 共用，避免两份 expiresIn 硬编码漂移。 */
    private TokenResponse issueTokens(User user) {
        var accessToken = jwtUtil.generateAccessToken(user.getId().longValue(), user.getUsername(), user.getRoleStatus());
        var refreshToken = jwtUtil.generateRefreshToken(user.getId().longValue(), user.getUsername());

        var userInfo = new TokenResponse.UserInfo();
        userInfo.setId(user.getId());
        userInfo.setUsername(user.getUsername());
        userInfo.setRealName(user.getRealName());
        userInfo.setAvatarUrl(user.getAvatarUrl());
        userInfo.setRoleStatus(user.getRoleStatus());

        var response = new TokenResponse();
        response.setAccessToken(accessToken);
        response.setRefreshToken(refreshToken);
        // 之前是写死的 900L，与 application.yml 的 jwt.access-token-expiration 两处维护，
        // 改配置忘了改代码就会不一致。现在唯一真源在 JwtUtil。
        response.setExpiresIn(jwtUtil.getAccessTokenExpiration() / 1000L);
        response.setUserInfo(userInfo);
        return response;
    }

    /** 已锁定则直接拒绝，不再走密码校验——锁定期间连查询用户都省掉。 */
    private void assertNotLocked(String failKey) {
        Object count = redisTemplate.opsForValue().get(failKey);
        if (count instanceof Number n && n.intValue() >= MAX_LOGIN_FAILURES) {
            Long ttl = redisTemplate.getExpire(failKey, TimeUnit.SECONDS);
            String remain = (ttl != null && ttl > 0) ? "，请 " + ((ttl + 59) / 60) + " 分钟后再试" : "";
            throw BusinessException.forbidden("该账号尝试次数过多" + remain);
        }
    }

    /**
     * 记一次登录失败。
     *
     * <p>计数窗口用 {@code expire} 而不是滑动窗口：第一次失败时设 15 分钟过期，
     * 之后每次 {@code increment} 都不重置 TTL。这样「15 分钟内满 5 次」才锁，
     * 而中间登录成功会清零。写起来简单，行为也好解释。
     */
    private void recordLoginFailure(String failKey) {
        Long current = redisTemplate.opsForValue().increment(failKey);
        if (current != null && current == 1L) {
            redisTemplate.expire(failKey, LOGIN_LOCK_MINUTES, TimeUnit.MINUTES);
        }
    }

    // 用户注册：Redis校验验证码 → 查重 → BCrypt加密 → 入库
    public void register(RegisterRequest request) {
        var codeKey = CODE_PREFIX + request.getEmail();
        var savedCode = (String) redisTemplate.opsForValue().get(codeKey);
        if (savedCode == null || !savedCode.equals(request.getCode())) {
            throw BusinessException.badRequest("验证码错误或已过期");
        }

        var existingUser = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, request.getUsername()));
        if (existingUser != null) {
            throw BusinessException.badRequest("用户名已存在");
        }

        var existingEmail = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, request.getEmail()));
        if (existingEmail != null) {
            throw BusinessException.badRequest("邮箱已被注册");
        }

        var user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRealName(request.getRealName() != null ? request.getRealName() : request.getUsername());
        user.setEmail(request.getEmail());
        user.setAge(request.getAge());
        user.setSex(request.getSex());
        user.setRoleStatus(MedicalConstants.ROLE_USER);
        user.setStatus(1);

        userMapper.insert(user);
        redisTemplate.delete(codeKey);
        log.info("新用户注册: {}", user.getUsername());
    }

    // 发送验证码：60s频率限制 → 生成6位随机数 → 存Redis(5分钟) → 发邮件
    public void sendVerificationCode(SendCodeRequest request) {
        var rateKey = CODE_RATE_PREFIX + request.getEmail();
        if (Boolean.TRUE.equals(redisTemplate.hasKey(rateKey))) {
            throw BusinessException.badRequest("验证码发送过于频繁，请60秒后再试");
        }

        var code = RandomUtil.randomNumbers(6);
        var codeKey = CODE_PREFIX + request.getEmail();

        redisTemplate.opsForValue().set(codeKey, code, CODE_EXPIRE_MINUTES, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set(rateKey, "1", 60, TimeUnit.SECONDS);

        emailClient.sendVerificationCode(request.getEmail(), code, CODE_EXPIRE_MINUTES);
        log.info("验证码已发送至: {}", request.getEmail());
    }
}
