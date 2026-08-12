package com.example.demo.security;

import com.example.demo.persistence.entity.UserEntity;
import com.example.demo.persistence.service.UserPersistenceService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer JWT 过滤器：验证 Token 后回查用户，最终角色只来自数据库。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log =
            LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserPersistenceService userPersistenceService;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            UserPersistenceService userPersistenceService,
            RestAuthenticationEntryPoint authenticationEntryPoint) {
        this.jwtService = jwtService;
        this.userPersistenceService = userPersistenceService;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String token = authorization.substring(BEARER_PREFIX.length()).trim();
            if (token.isBlank()) {
                throw new BadCredentialsException("Bearer Token 为空");
            }
            Jwt jwt = jwtService.decode(token);
            AuthenticatedUser principal = loadAndVerifyUser(jwt);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            List.of(new SimpleGrantedAuthority(
                                    principal.role().authority())));
            authentication.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | BadCredentialsException | IllegalArgumentException e) {
            SecurityContextHolder.clearContext();
            log.warn("JWT 认证失败 | path={} reason={}", request.getRequestURI(), e.getMessage());
            authenticationEntryPoint.commence(
                    request,
                    response,
                    new BadCredentialsException("JWT 认证失败", e));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private AuthenticatedUser loadAndVerifyUser(Jwt jwt) {
        String username = jwt.getSubject();
        UserEntity user = userPersistenceService.findByUsername(username);
        if (user == null) {
            throw new BadCredentialsException("Token 对应用户不存在");
        }

        Number tokenUserId = jwt.getClaim("uid");
        UserRole tokenRole = UserRole.from(jwt.getClaimAsString("role"));
        UserRole databaseRole = UserRole.from(user.getRole());
        if (tokenUserId == null
                || !Objects.equals(tokenUserId.longValue(), user.getId())
                || tokenRole != databaseRole) {
            throw new BadCredentialsException("Token 中的用户身份或角色已失效");
        }
        return new AuthenticatedUser(user.getId(), user.getUsername(), databaseRole);
    }
}
