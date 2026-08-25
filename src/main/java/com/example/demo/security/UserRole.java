package com.example.demo.security;

import java.util.Locale;

public enum UserRole {
    ADMIN,
    ANALYST,
    USER,
    GUEST;

    public static UserRole from(String role) {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("用户角色不能为空");
        }
        try {
            return valueOf(role.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的用户角色: " + role, e);
        }
    }

    public String authority() {
        return "ROLE_" + name();
    }
}
