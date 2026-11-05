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
 * 认证服务：登录、注册(JWT双Token)、邮箱验证码(Redis TTL + 60s频率限制)
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

    // 用户登录：校验账号状态 → BCrypt验密 → 生成双Token
    public TokenResponse login(LoginRequest request) {
        var user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, request.getUsername()));
        if (user == null) {
            throw BusinessException.unauthorized("用户名或密码错误");
        }
        if (user.getStatus() == 0) {
            throw BusinessException.forbidden("账号已被禁用");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw BusinessException.unauthorized("用户名或密码错误");
        }

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
        response.setExpiresIn(900L);
        response.setUserInfo(userInfo);
        return response;
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
