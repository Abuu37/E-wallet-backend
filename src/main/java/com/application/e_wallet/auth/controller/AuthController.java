package com.application.e_wallet.auth.controller;

import com.application.e_wallet.auth.dto.*;
import com.application.e_wallet.auth.service.AuthService;
import com.application.e_wallet.auth.service.PasswordResetService;
import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.security.jwt.AuthenticatedUser;
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
    private final PasswordResetService passwordResetService;

    @PostMapping("/customer-registration")
    public ResponseEntity<ApiResponse<RegistrationResponse>> registerCustomer(@Valid @RequestBody CustomerRegistrationRequest request){

        RegistrationResponse response = authService.registerCustomer(request);

        ApiResponse<RegistrationResponse> body = ApiResponse.<RegistrationResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.CREATED.value())
                .message("Customer registered successfully")
                .data(response)
                .build();

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(body);
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request){
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
                .message("Account status successfully changed")
                .data(response)
                .build();

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(body);
    }

    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse<Void>> changePassword(@Valid @RequestBody ChangePasswordRequest request, Authentication authentication){
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        authService.changePassword(principal.email(), request);

        ApiResponse<Void> body = ApiResponse.<Void>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Password changed successfully")
                .build();

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(body);
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request){
        passwordResetService.forgotPassword(request.email());

        ApiResponse<Void> body = ApiResponse.<Void>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("If an account exists for this email, a password reset code has been sent.")
                .build();

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(body);
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequest request){
        passwordResetService.resetPassword(
                request.email(), request.otp(), request.newPassword()
        );

        ApiResponse<Void> body = ApiResponse.<Void>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Password reset successfully")
                .build();

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(body);
    }

}


