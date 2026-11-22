package com.application.e_wallet.payment.controller;

import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.payment.dto.CheckoutSessionResponse;
import com.application.e_wallet.payment.dto.CreateCheckoutSessionRequest;
import com.application.e_wallet.payment.service.PaymentService;
import com.application.e_wallet.security.jwt.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/payments/stripe")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/checkout-session")
    @PreAuthorize("hasAuthority('WALLET_TO_BANK_TRANSFER')")
    public ResponseEntity<ApiResponse<CheckoutSessionResponse>> createCheckoutSession(
            @Valid @RequestBody CreateCheckoutSessionRequest request, Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();

        CheckoutSessionResponse response = paymentService.createCheckoutSession(request, principal.userId());

        ApiResponse<CheckoutSessionResponse> body = ApiResponse.<CheckoutSessionResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Checkout session created.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(
            @RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        paymentService.handleWebhookEvent(payload, signature);
        return ResponseEntity.ok().build();
    }
}
