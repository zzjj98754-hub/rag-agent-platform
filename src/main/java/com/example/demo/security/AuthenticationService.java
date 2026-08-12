package com.example.demo.security;

import com.example.demo.dto.LoginRequest;
import com.example.demo.dto.LoginResponse;
import com.example.demo.dto.LoginResponse.UserInfo;
import com.example.demo.persistence.entity.UserEntity;
import com.example.demo.persistence.service.UserPersistenceService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationService {

    private final UserPersistenceService userPersistenceService;
    private final JwtService jwtService;

    public AuthenticationService(
            UserPersistenceService userPersistenceService,
            JwtService jwtService) {
        this.userPersistenceService = userPersistenceService;
        this.jwtService = jwtService;
    }

    public LoginResponse login(LoginRequest request) {
        if (request == null
                || request.getUsername() == null
                || request.getUsername().isBlank()
                || request.getPassword() == null
                || request.getPassword().isBlank()) {
            throw new BadCredentialsException("用户名或密码错误");
        }

        UserEntity user = userPersistenceService.findByUsername(request.getUsername());
        if (!userPersistenceService.matchesPassword(user, request.getPassword())) {
            throw new BadCredentialsException("用户名或密码错误");
        }

        UserRole role;
        try {
            role = UserRole.from(user.getRole());
        } catch (IllegalArgumentException e) {
            throw new BadCredentialsException("用户角色配置无效", e);
        }
        AuthenticatedUser principal =
                new AuthenticatedUser(user.getId(), user.getUsername(), role);
        JwtService.IssuedToken token = jwtService.issue(principal);
        return new LoginResponse(
                token.value(),
                "Bearer",
                token.expiresIn(),
                token.expiresAt(),
                new UserInfo(user.getId(), user.getUsername(), role.name()));
    }
}
