package com.application.e_wallet.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JwtService {

    public static final String ACCESS_TOKEN_TYPE = "ACCESS TOKEN";
    public static final String REFRESH_TOKEN_TYPE = "REFRESH TOKEN";

    private final JwtProperties jwtProperties;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(
                jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8)

        );
    }

    public String generateAccessToken(UUID userId, String email, String roles) {
        return generateToken(
                userId,
                email,
                roles,
                ACCESS_TOKEN_TYPE,
                jwtProperties.getAccessTokenExpiration()
                );
    }

    public String generateRefreshToken(UUID userId, String email) {
        return generateToken(
                userId,
                email,
                null,
                REFRESH_TOKEN_TYPE,
                jwtProperties.getRefreshTokenExpiration()

                );
    }

    // Checks if a specific token string is mathematically valid, not expired, and untampered with (Validation).
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey()) // check valid signature
                .build()
                .parseSignedClaims(token) // check expiration
                .getPayload();
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(parseToken(token).getSubject());
    }

    public String extractRoles(String token) {
        return parseToken(token).get("roles", String.class);
    }

    public String extractTokenType(String token) {
        return parseToken(token).get("type", String.class);
    }

    public String extractEmail(String token) {
        return parseToken(token).get("email", String.class);
    }

    private String generateToken(UUID userId, String email, String roles, String tokenType, long expiration) {

        Date now = new Date();
        Date expiry = new Date(now.getTime() + expiration);

        Map<String, Object> claims = new HashMap<>();
        claims.put("email", email);
        claims.put("type", tokenType);
        if (roles != null) {
            claims.put("roles", roles);
        }

        return Jwts.builder()
                .subject(userId.toString())
                .claims(claims)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getSigningKey())
                .compact();
    }




}
