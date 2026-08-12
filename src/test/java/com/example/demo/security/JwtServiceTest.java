package com.example.demo.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.config.JwtConfig;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

class JwtServiceTest {

    @Test
    void shouldIssueSignedTokenWithUserIdentityAndRole() {
        JwtConfig config = new JwtConfig();
        SecretKey key = config.jwtSecretKey(
                "test-only-jwt-secret-with-at-least-thirty-two-bytes");
        JwtEncoder encoder = config.jwtEncoder(key);
        JwtDecoder decoder = config.jwtDecoder(key, "test-issuer");
        JwtService service =
                new JwtService(encoder, decoder, "test-issuer", 3_600);

        JwtService.IssuedToken issued = service.issue(
                new AuthenticatedUser(7L, "alice", UserRole.ADMIN));
        Jwt decoded = service.decode(issued.value());

        assertThat(decoded.getSubject()).isEqualTo("alice");
        assertThat(decoded.getClaimAsString("role")).isEqualTo("ADMIN");
        assertThat(((Number) decoded.getClaim("uid")).longValue()).isEqualTo(7L);
        assertThat(issued.expiresIn()).isEqualTo(3_600);
    }
}
