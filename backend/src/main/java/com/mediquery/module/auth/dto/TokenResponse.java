package com.mediquery.module.auth.dto;

import lombok.Data;

/**
 * 登录响应：accessToken(15min) + refreshToken(7d) + 用户基本信息
 */
@Data
public class TokenResponse {
    private String accessToken;
    private String refreshToken;
    private long expiresIn;
    private UserInfo userInfo;

    @Data
    public static class UserInfo {
        private Integer id;
        private String username;
        private String realName;
        private String avatarUrl;
        private Integer roleStatus;
    }
}
