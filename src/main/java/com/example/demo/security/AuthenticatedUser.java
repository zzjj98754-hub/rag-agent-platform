package com.example.demo.security;

public record AuthenticatedUser(
        Long id,
        String username,
        UserRole role) {}
