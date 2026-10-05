package com.application.e_wallet.auth.controller;

import com.application.e_wallet.auth.dto.CustomerRegistrationRequest;
import com.application.e_wallet.auth.dto.LoginRequest;
import com.application.e_wallet.auth.dto.LoginResponse;
import com.application.e_wallet.auth.dto.RegistrationResponse;
import com.application.e_wallet.auth.service.AuthService;
import com.application.e_wallet.common.dto.ApiResponse;
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
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

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
}


