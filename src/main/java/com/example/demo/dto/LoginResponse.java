package com.example.demo.dto;

import java.time.Instant;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        Instant expiresAt,
        UserInfo user) {

    public record UserInfo(
            Long id,
            String username,
            String role) {}
}
