package com.application.e_wallet.otp.controller;

import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.otp.dto.ResendOtpRequest;
import com.application.e_wallet.otp.dto.VerifyOtpRequest;
import com.application.e_wallet.otp.service.OtpService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger logger = LoggerFactory.getLogger(OtpController.class);

    private final OtpService otpService;

    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<Void>> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {

        logger.info("Received OTP verification request for email: {}", request.email());

        otpService.verifyOtp(request.email(), request.otp());

        logger.info("OTP verified successfully for email: {}", request.email());

        ApiResponse<Void> body = ApiResponse.<Void>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Email verified. Please set your PIN to activate your account.")
                .build();

        return ResponseEntity.ok(body);
    }

    @PostMapping("/resend")
    public ResponseEntity<ApiResponse<Void>> resendOtp(@Valid @RequestBody ResendOtpRequest request) {
        logger.info("Received OTP resend request for email: {}", request.email());

        otpService.resendOtp(request.email());

        ApiResponse<Void> body = ApiResponse.<Void>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("New OTP has been sent to your email.")
                .build();

        return ResponseEntity.ok(body);
    }
}
