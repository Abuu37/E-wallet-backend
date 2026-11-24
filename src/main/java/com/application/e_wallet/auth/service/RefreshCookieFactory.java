package com.application.e_wallet.auth.service;

import com.application.e_wallet.auth.config.RefreshCookieProperties;
import com.application.e_wallet.security.jwt.JwtProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class RefreshCookieFactory {

    private final RefreshCookieProperties properties;
    private final JwtProperties jwtProperties;

    public ResponseCookie create(String refreshToken) {
        return base(refreshToken)
                .maxAge(Duration.ofMillis(jwtProperties.getRefreshTokenExpiration()))
                .build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(properties.getName(), value)
                .httpOnly(true)
                .secure(properties.isSecure())
                .sameSite("Strict")
                .path(properties.getPath());
    }
}
