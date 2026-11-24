package com.application.e_wallet.auth.dto;

/** Login/refresh outcome: the JSON body for the client plus the refresh token, which only ever travels in a cookie. */
public record AuthResult(LoginResponse body, String refreshToken) {
}
