package com.application.e_wallet.security.jwt;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtProperties jwtProperties;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(
                jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8)

        );
    }

    public String generateAccessToken(UUID userId, String email) {
        return generateToken(
                userId,
                email,
                "ACCESS TOKEN",
                jwtProperties.getAccessTokenExpiration()
                );
    }

    public String generateRefreshToken(UUID userId, String email) {
        return generateToken(
                userId,
                email,
                "REFRESH TOKEN",
                jwtProperties.getRefreshTokenExpiration()

                );
    }

    private String generateToken(UUID userId, String email, String tokenType, long expiration) {

        Date now = new Date();
        Date expiry = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(userId.toString())
                .claims(Map.of(
                        "email", email,
                        "type", tokenType
                ))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getSigningKey())
                .compact();
    }




}
