package com.example.demo.persistence.service;

import com.example.demo.persistence.entity.UserEntity;
import com.example.demo.persistence.mapper.UserMapper;
import com.example.demo.security.UserRole;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserPersistenceService {

    private static final String DEFAULT_ROLE = "USER";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public UserPersistenceService(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserEntity createUser(String username, String rawPassword, String role) {
        String normalizedUsername = requireText(username, "username", 64);
        String normalizedPassword = requirePassword(rawPassword);
        if (normalizedPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("password 不能超过 72 个 UTF-8 字节");
        }
        if (userMapper.findByUsername(normalizedUsername) != null) {
            throw new IllegalArgumentException("用户名已存在: " + normalizedUsername);
        }

        UserEntity user = new UserEntity();
        user.setUsername(normalizedUsername);
        user.setPassword(passwordEncoder.encode(normalizedPassword));
        user.setRole(normalizeRole(role));
        userMapper.insert(user);
        return userMapper.findById(user.getId());
    }

    public UserEntity findById(Long id) {
        return id == null ? null : userMapper.findById(id);
    }

    public UserEntity findByUsername(String username) {
        return username == null || username.isBlank()
                ? null
                : userMapper.findByUsername(username.trim());
    }

    public boolean matchesPassword(UserEntity user, String rawPassword) {
        return user != null
                && rawPassword != null
                && passwordEncoder.matches(rawPassword, user.getPassword());
    }

    @Transactional
    public void updatePassword(Long userId, String rawPassword) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        String normalizedPassword = requirePassword(rawPassword);
        if (normalizedPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("password 不能超过 72 个 UTF-8 字节");
        }
        String encoded = passwordEncoder.encode(normalizedPassword);
        if (userMapper.updatePassword(userId, encoded) == 0) {
            throw new IllegalArgumentException("用户不存在: " + userId);
        }
    }

    @Transactional
    public void updateRole(Long userId, String role) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (userMapper.updateRole(userId, normalizeRole(role)) == 0) {
            throw new IllegalArgumentException("用户不存在: " + userId);
        }
    }

    private String normalizeRole(String role) {
        return UserRole.from(
                role == null || role.isBlank() ? DEFAULT_ROLE : role).name();
    }

    private String requirePassword(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("password 不能为空");
        }
        return rawPassword;
    }

    private String requireText(String value, String fieldName) {
        return requireText(value, fieldName, Integer.MAX_VALUE);
    }

    private String requireText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    fieldName + " 不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }
}
