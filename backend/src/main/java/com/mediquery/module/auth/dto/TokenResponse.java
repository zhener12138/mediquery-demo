package com.mediquery.module.auth.dto;

import lombok.Data;

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
