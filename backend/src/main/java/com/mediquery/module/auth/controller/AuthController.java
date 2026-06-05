package com.mediquery.module.auth.controller;

import com.mediquery.common.dto.Result;
import com.mediquery.module.auth.dto.LoginRequest;
import com.mediquery.module.auth.dto.RegisterRequest;
import com.mediquery.module.auth.dto.SendCodeRequest;
import com.mediquery.module.auth.dto.TokenResponse;
import com.mediquery.module.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器：登录(JWT双Token)、注册(邮箱验证码)、发送验证码
 */
@Tag(name = "认证管理")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) { this.authService = authService; }

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public Result<TokenResponse> login(@Valid @RequestBody LoginRequest request) { return Result.success(authService.login(request)); }

    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public Result<?> register(@Valid @RequestBody RegisterRequest request) { authService.register(request); return Result.success(); }

    @Operation(summary = "发送邮箱验证码")
    @PostMapping("/send-code")
    public Result<String> sendCode(@Valid @RequestBody SendCodeRequest request) { authService.sendVerificationCode(request); return Result.success("验证码已发送"); }
}
