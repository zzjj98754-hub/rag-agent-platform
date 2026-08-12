package com.example.demo.security;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;
    private final String issuer;
    private final long expirationSeconds;

    public JwtService(
            JwtEncoder jwtEncoder,
            JwtDecoder jwtDecoder,
            @Value("${app.security.jwt.issuer}") String issuer,
            @Value("${app.security.jwt.expiration-seconds}") long expirationSeconds) {
        this.jwtEncoder = jwtEncoder;
        this.jwtDecoder = jwtDecoder;
        this.issuer = issuer;
        this.expirationSeconds = Math.max(60, expirationSeconds);
    }

    public IssuedToken issue(AuthenticatedUser user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(expirationSeconds);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(user.username())
                .claim("uid", user.id())
                .claim("role", user.role().name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256)
                .type("JWT")
                .build();
        String token = jwtEncoder.encode(
                JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expirationSeconds, expiresAt);
    }

    public Jwt decode(String token) {
        return jwtDecoder.decode(token);
    }

    public record IssuedToken(
            String value,
            long expiresIn,
            Instant expiresAt) {}
}
