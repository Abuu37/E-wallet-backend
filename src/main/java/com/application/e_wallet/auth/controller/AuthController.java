package com.application.e_wallet.auth.controller;

import com.application.e_wallet.auth.dto.*;
import com.application.e_wallet.auth.service.AuthService;
import com.application.e_wallet.auth.service.PinResetService;
import com.application.e_wallet.auth.service.RateLimitingService;
import com.application.e_wallet.auth.service.RefreshCookieFactory;
import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.security.jwt.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final PinResetService pinResetService;
    private final RateLimitingService rateLimitingService;
    private final RefreshCookieFactory refreshCookieFactory;

    private static <T> ApiResponse<T> ok(String message, T data) {
        return ApiResponse.<T>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message(message)
                .data(data)
                .build();
    }

    @PostMapping("/set-pin")
    public ResponseEntity<ApiResponse<UserProfileResponse>> setPin(@Valid @RequestBody SetPinRequest request, HttpServletRequest httpRequest) {
        rateLimitingService.enforce(httpRequest.getRemoteAddr(), "set-pin:" + request.getEmail());
        return ResponseEntity.ok(ok("Customer registered successfully. You can now log in.", authService.setPin(request)));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        rateLimitingService.enforce(httpRequest.getRemoteAddr(), "login:" + request.getPhone());

        AuthResult result = authService.login(request);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookieFactory.create(result.refreshToken()).toString())
                .body(ok("Login successful", result.body()));
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<AccountStatusResponse>> accountStatus(Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        return ResponseEntity.ok(ok("Account status retrieved successfully", authService.getAccountStatus(principal.email())));
    }

    @PostMapping("/change-pin")
    public ResponseEntity<ApiResponse<Void>> changePin(@Valid @RequestBody ChangePinRequest request, Authentication authentication, HttpServletRequest httpRequest) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        rateLimitingService.enforce(httpRequest.getRemoteAddr(), "change-pin:" + principal.email());
        authService.changePin(principal.userId(), request);
        return ResponseEntity.ok(ok("PIN changed successfully", null));
    }

    @PostMapping("/forgot-pin")
    public ResponseEntity<ApiResponse<Void>> forgotPin(@Valid @RequestBody ForgotPinRequest request, HttpServletRequest httpRequest) {
        rateLimitingService.enforce(httpRequest.getRemoteAddr(), "forgot-pin:" + request.phone());
        pinResetService.forgotPin(request.phone(), request.nidaLast4());
        // The same answer whether or not the details matched, and no email is shown.
        return ResponseEntity.ok(ok("If these details match an account, we've sent a code to the email on file.", null));
    }

    @PostMapping("/reset-pin")
    public ResponseEntity<ApiResponse<Void>> resetPin(@Valid @RequestBody ResetPinRequest request, HttpServletRequest httpRequest) {
        rateLimitingService.enforce(httpRequest.getRemoteAddr(), "reset-pin:" + request.phone());
        pinResetService.resetPin(request.phone(), request.otp(), request.newPin());
        return ResponseEntity.ok(ok("PIN reset successfully", null));
    }

    /**
     * Cookie-authenticated, so CSRF-protected (see SecurityConfig). The refresh token is read
     * from the httpOnly cookie and rotated: a new cookie replaces it on every call.
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(
            @CookieValue(name = "${app.auth.refresh-cookie.name:wp_refresh}", required = false) String refreshToken,
            HttpServletRequest httpRequest) {

        rateLimitingService.enforceRefresh(httpRequest.getRemoteAddr());

        AuthResult result = authService.refreshToken(refreshToken);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookieFactory.create(result.refreshToken()).toString())
                .body(ok("Token refreshed successfully", result.body()));
    }

    /** Public so an expired access token can still sign out; identified by the cookie alone. CSRF-protected. */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @CookieValue(name = "${app.auth.refresh-cookie.name:wp_refresh}", required = false) String refreshToken,
            HttpServletRequest httpRequest) {

        rateLimitingService.enforce(httpRequest.getRemoteAddr(), null);
        authService.logout(refreshToken);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookieFactory.clear().toString())
                .body(ok("Logged out successfully", null));
    }

    /** Lets the SPA obtain the XSRF-TOKEN cookie before its first refresh; the CSRF filter writes it. */
    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf() {
        return ResponseEntity.noContent().build();
    }
}
