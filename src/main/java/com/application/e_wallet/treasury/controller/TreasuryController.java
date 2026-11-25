package com.application.e_wallet.treasury.controller;

import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.security.jwt.AuthenticatedUser;
import com.application.e_wallet.treasury.dto.CommissionTopupRequest;
import com.application.e_wallet.treasury.dto.CommissionTopupResponse;
import com.application.e_wallet.treasury.dto.TransactionJournalResponse;
import com.application.e_wallet.treasury.dto.TreasuryOverviewResponse;
import com.application.e_wallet.treasury.service.TreasuryRequestService;
import com.application.e_wallet.treasury.service.TreasuryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/treasury")
@RequiredArgsConstructor
public class TreasuryController {

    private final TreasuryService treasuryService;
    private final TreasuryRequestService treasuryRequestService;
    private final com.application.e_wallet.common.idempotency.IdempotencyService idempotencyService;

    @GetMapping
    @PreAuthorize("hasAuthority('COMPANY_WALLET_VIEW')")
    public ResponseEntity<ApiResponse<TreasuryOverviewResponse>> getOverview(Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        return ResponseEntity.ok(ok("Treasury overview retrieved successfully", treasuryService.getOverview(principal.email())));
    }

    @GetMapping("/journal")
    @PreAuthorize("hasAuthority('LEDGER_VIEW')")
    public ResponseEntity<ApiResponse<List<TransactionJournalResponse>>> getJournal() {
        return ResponseEntity.ok(ok("Transaction journal retrieved successfully", treasuryService.getJournalHistory()));
    }

    /** Refill the commission pool from revenue. One finance staff member can do it; no second approver. */
    @PostMapping("/commission/topup")
    @PreAuthorize("hasAuthority('COMMISSION_MANAGE')")
    public ResponseEntity<ApiResponse<CommissionTopupResponse>> refillCommissionPool(
            @Valid @RequestBody CommissionTopupRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        CommissionTopupResponse response = idempotencyService.execute(principal.userId(), idempotencyKey, "POST /treasury/commission/topup", request,
                CommissionTopupResponse.class, () -> treasuryService.fundCommission(request.amount(), request.description(), principal.userId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ok("Commission pool refilled from revenue.", response));
    }

    private static <T> ApiResponse<T> ok(String message, T data) {
        return ApiResponse.<T>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message(message)
                .data(data)
                .build();
    }
}
