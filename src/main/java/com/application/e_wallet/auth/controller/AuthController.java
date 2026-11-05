package com.application.e_wallet.auth.controller;

import com.application.e_wallet.auth.dto.*;
import com.application.e_wallet.auth.service.AuthService;
import com.application.e_wallet.auth.service.PinResetService;
import com.application.e_wallet.auth.service.RateLimitingService;
import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.common.exception.TooManyRequestsException;
import com.application.e_wallet.security.jwt.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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


    @PostMapping("/customer-registration")
    public ResponseEntity<ApiResponse<RegistrationResponse>> registerCustomer(@Valid @RequestBody CustomerRegistrationRequest request, HttpServletRequest httpRequest){

        String ip = httpRequest.getRemoteAddr();

        if (!rateLimitingService.tryConsumeIp(ip) || !rateLimitingService.tryConsumeAccount(request.getEmail())) {
            throw new TooManyRequestsException("Too many registration attempts. Please try again later.");
        }

        RegistrationResponse response = authService.registerCustomer(request);

        ApiResponse<RegistrationResponse> body = ApiResponse.<RegistrationResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.CREATED.value())
                .message("OTP has been sent to your email.")
                .data(response)
                .build();

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(body);
    }

    @PostMapping("/set-pin")
    public ResponseEntity<ApiResponse<UserProfileResponse>> setPin(@Valid @RequestBody SetPinRequest request, HttpServletRequest httpRequest){

        String ip = httpRequest.getRemoteAddr();

        if (!rateLimitingService.tryConsumeIp(ip) || !rateLimitingService.tryConsumeAccount(request.getEmail())) {
            throw new TooManyRequestsException("Too many attempts. Please try again later.");
        }

        UserProfileResponse response = authService.setPin(request);

        ApiResponse<UserProfileResponse> body = ApiResponse.<UserProfileResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Customer registered successfully. You can now log in.")
                .data(response)
                .build();

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(body);
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest){

        String ip = httpRequest.getRemoteAddr();

        if (!rateLimitingService.tryConsumeIp(ip) || !rateLimitingService.tryConsumeAccount(request.getPhone())) {
            throw new TooManyRequestsException("Too many login attempts. Please try again later.");
        }

        LoginResponse response = authService.login(request);

        ApiResponse<LoginResponse> body = ApiResponse.<LoginResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Login successful")
                .data(response)
                .build();

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(body);
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<AccountStatusResponse>> accountStatus(Authentication authentication){
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        AccountStatusResponse response = authService.getAccountStatus(principal.email());

        ApiResponse<AccountStatusResponse> body = ApiResponse.<AccountStatusResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Account status retrieved successfully")
                .data(response)
                .build();


        return ResponseEntity
                .status(HttpStatus.OK)
                .body(body);
    }

    @PostMapping("/change-pin")
    public ResponseEntity<ApiResponse<Void>> changePin(@Valid @RequestBody ChangePinRequest request, Authentication authentication, HttpServletRequest httpRequest){
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();

        String ip = httpRequest.getRemoteAddr();

        if (!rateLimitingService.tryConsumeIp(ip) || !rateLimitingService.tryConsumeAccount(principal.email())) {
            throw new TooManyRequestsException("Too many attempts. Please try again later.");
        }

        authService.changePin(principal.email(), request);

        ApiResponse<Void> body = ApiResponse.<Void>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("PIN changed successfully")
                .build();

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(body);
    }

    @PostMapping("/forgot-pin")
    public ResponseEntity<ApiResponse<Void>> forgotPin(@Valid @RequestBody ForgotPinRequest request, HttpServletRequest httpRequest){

        String ip = httpRequest.getRemoteAddr();

        if (!rateLimitingService.tryConsumeIp(ip) || !rateLimitingService.tryConsumeAccount(request.email())) {
            throw new TooManyRequestsException("Too many requests. Please try again later.");
        }

        pinResetService.forgotPin(request.email());

        ApiResponse<Void> body = ApiResponse.<Void>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("OTP has been sent to your email.")
                .build();

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(body);
    }

    @PostMapping("/reset-pin")
    public ResponseEntity<ApiResponse<Void>> resetPin(@Valid @RequestBody ResetPinRequest request, HttpServletRequest httpRequest){

        String ip = httpRequest.getRemoteAddr();

        if (!rateLimitingService.tryConsumeIp(ip) || !rateLimitingService.tryConsumeAccount(request.email())) {
            throw new TooManyRequestsException("Too many attempts. Please try again later.");
        }

        pinResetService.resetPin(
                request.email(), request.otp(), request.newPin()
        );

        ApiResponse<Void> body = ApiResponse.<Void>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("PIN reset successfully")
                .build();

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(body);
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(@Valid @RequestBody RefreshTokenRequest request, HttpServletRequest httpRequest){

        String ip = httpRequest.getRemoteAddr();

        if (!rateLimitingService.tryConsumeIp(ip)) {
            throw new TooManyRequestsException("Too many requests. Please try again later.");
        }

        LoginResponse response = authService.refreshToken(request.getRefreshToken());

        ApiResponse<LoginResponse> body = ApiResponse.<LoginResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Token refreshed successfully")
                .data(response)
                .build();

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(body);
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@Valid @RequestBody LogoutRequest request, Authentication authentication){
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        authService.logout(principal.email(), request.getRefreshToken());

        ApiResponse<Void> body = ApiResponse.<Void>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Logged out successfully")
                .build();

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(body);
    }

}


