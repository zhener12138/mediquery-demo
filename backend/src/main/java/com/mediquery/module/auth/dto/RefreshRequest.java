package com.mediquery.module.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 用 refreshToken 换新 token 的请求体。
 *
 * <p>为什么 refreshToken 走 body 而不是 Authorization 头：
 * 头里那个位置是给 accessToken 的，两者都塞同一个 header 会让
 * {@code JwtAuthenticationFilter} 分不清该拦还是该放。放 body 最省事，
 * 也避免被某些中间层顺手记进访问日志。
 */
public class RefreshRequest {

    @NotBlank(message = "refreshToken 不能为空")
    private String refreshToken;

    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
}
