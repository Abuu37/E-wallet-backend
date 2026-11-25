package com.application.e_wallet.otp.controller;

import com.application.e_wallet.auth.service.RateLimitingService;
import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.otp.dto.ResendOtpRequest;
import com.application.e_wallet.otp.dto.VerifyOtpRequest;
import com.application.e_wallet.otp.service.OtpService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/otp")
@RequiredArgsConstructor
public class OtpController {

    private final OtpService otpService;
    private final RateLimitingService rateLimitingService;

    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<Void>> verifyOtp(@Valid @RequestBody VerifyOtpRequest request, HttpServletRequest httpRequest) {

        rateLimitingService.enforce(httpRequest.getRemoteAddr(), "otp-verify:" + request.phone());

        otpService.verifyOtp(request.phone(), request.otp());

        ApiResponse<Void> body = ApiResponse.<Void>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Code accepted.")
                .build();

        return ResponseEntity.ok(body);
    }

    @PostMapping("/resend")
    public ResponseEntity<ApiResponse<Void>> resendOtp(@Valid @RequestBody ResendOtpRequest request, HttpServletRequest httpRequest) {

        rateLimitingService.enforce(httpRequest.getRemoteAddr(), "otp-resend:" + request.phone());

        otpService.resendOtp(request.phone());

        ApiResponse<Void> body = ApiResponse.<Void>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("If this number can receive a code, we sent a new one to the email on file.")
                .build();

        return ResponseEntity.ok(body);
    }
}
