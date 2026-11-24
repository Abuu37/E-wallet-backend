package com.application.e_wallet.kyc.controller;

import com.application.e_wallet.auth.service.RateLimitingService;
import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.kyc.dto.KycLinkTokenResponse;
import com.application.e_wallet.kyc.dto.KycStatusResponse;
import com.application.e_wallet.kyc.service.KycService;
import com.application.e_wallet.security.jwt.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Customer identity verification. Every call acts on the signed-in customer only; there is no id in the URL. */
@RestController
@RequestMapping("/api/v1/kyc")
@RequiredArgsConstructor
public class KycController {

    private final KycService kycService;
    private final RateLimitingService rateLimitingService;

    @PostMapping("/plaid/link-token")
    @PreAuthorize("hasAuthority('CUSTOMER_UPDATE')")
    public ResponseEntity<ApiResponse<KycLinkTokenResponse>> linkToken(Authentication authentication, HttpServletRequest http) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        rateLimitingService.enforce(http.getRemoteAddr(), "kyc:" + principal.userId());
        String token = kycService.startSession(principal.userId());
        return ResponseEntity.ok(ok("Verification session created", new KycLinkTokenResponse(token)));
    }

    /** The client only says it finished. The result is read from the provider, never taken from the request. */
    @PostMapping("/plaid/complete")
    @PreAuthorize("hasAuthority('CUSTOMER_UPDATE')")
    public ResponseEntity<ApiResponse<KycStatusResponse>> complete(Authentication authentication, HttpServletRequest http) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        rateLimitingService.enforce(http.getRemoteAddr(), "kyc:" + principal.userId());
        return ResponseEntity.ok(ok("Verification checked", kycService.complete(principal.userId())));
    }

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('CUSTOMER_VIEW')")
    public ResponseEntity<ApiResponse<KycStatusResponse>> status(Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        return ResponseEntity.ok(ok("Verification status retrieved", kycService.status(principal.userId())));
    }

    private static <T> ApiResponse<T> ok(String message, T data) {
        return ApiResponse.<T>builder().timestamp(Instant.now()).status(HttpStatus.OK.value()).message(message).data(data).build();
    }
}
