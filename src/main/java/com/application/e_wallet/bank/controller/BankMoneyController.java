package com.application.e_wallet.bank.controller;

import com.application.e_wallet.auth.service.RateLimitingService;
import com.application.e_wallet.bank.dto.BankMoveRequest;
import com.application.e_wallet.bank.dto.BankMoveResponse;
import com.application.e_wallet.bank.service.BankMoneyService;
import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.common.idempotency.IdempotencyService;
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

/** Add money from, or withdraw to, the customer's own linked bank. Both need the Idempotency-Key header. */
@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class BankMoneyController {

    private final BankMoneyService bankMoneyService;
    private final IdempotencyService idempotencyService;
    private final RateLimitingService rateLimitingService;

    @PostMapping("/bank-topup")
    @PreAuthorize("hasAuthority('BANK_TOPUP')")
    public ResponseEntity<ApiResponse<BankMoveResponse>> topUp(@Valid @RequestBody BankMoveRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        rateLimitingService.enforceMoney(principal.userId());
        BankMoveResponse response = idempotencyService.execute(principal.userId(), idempotencyKey, "POST /transactions/bank-topup", request,
                BankMoveResponse.class, () -> bankMoneyService.topUp(request, principal.userId()));
        return ok("Money added from your bank.", response);
    }

    @PostMapping("/bank-withdraw")
    @PreAuthorize("hasAuthority('WITHDRAW_REQUEST')")
    public ResponseEntity<ApiResponse<BankMoveResponse>> withdraw(@Valid @RequestBody BankMoveRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        rateLimitingService.enforceMoney(principal.userId());
        BankMoveResponse response = idempotencyService.execute(principal.userId(), idempotencyKey, "POST /transactions/bank-withdraw", request,
                BankMoveResponse.class, () -> bankMoneyService.withdraw(request, principal.userId()));
        return ok("Withdrawal sent to your bank.", response);
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(String message, T data) {
        return ResponseEntity.ok(ApiResponse.<T>builder().timestamp(Instant.now()).status(HttpStatus.OK.value()).message(message).data(data).build());
    }
}
