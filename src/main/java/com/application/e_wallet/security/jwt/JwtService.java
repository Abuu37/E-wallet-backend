package com.application.e_wallet.security.jwt;

import com.application.e_wallet.role.entity.RoleEntity;
import com.application.e_wallet.user.entity.UserEntity;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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

    public String generateAccessToken(UserEntity user) {
        return generateToken(user, ACCESS_TOKEN_TYPE, jwtProperties.getAccessTokenExpiration());
    }

    public String generateRefreshToken(UserEntity user) {
        return generateToken(user, REFRESH_TOKEN_TYPE, jwtProperties.getRefreshTokenExpiration());
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

    private String generateToken(UserEntity user, String tokenType, long expirationMillis) {

        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMillis);

        String roles = user.getRoles().stream()
                .map(RoleEntity::getName)
                .collect(Collectors.joining(","));

        Map<String, Object> claims = new HashMap<>();
        claims.put("type", tokenType);
        claims.put("email", user.getEmail());
        claims.put("roles", roles);

        return Jwts.builder()
                .subject(user.getId().toString())
                .claims(claims)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getSigningKey())
                .compact();
    }


}
