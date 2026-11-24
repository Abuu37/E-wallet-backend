package com.application.e_wallet.treasury.controller;

import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.security.jwt.AuthenticatedUser;
import com.application.e_wallet.treasury.dto.CommissionTopupRequest;
import com.application.e_wallet.treasury.dto.MintRequest;
import com.application.e_wallet.treasury.dto.TransactionJournalResponse;
import com.application.e_wallet.treasury.dto.TreasuryOverviewResponse;
import com.application.e_wallet.treasury.dto.TreasuryRequestResponse;
import com.application.e_wallet.treasury.entity.RequestType;
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

/**
 * Mint and commission top-up are maker-checker: these endpoints only RAISE a request. A different
 * staff member then approves it through /treasury/requests/{id}/approve, which posts the journal.
 */
@RestController
@RequestMapping("/api/v1/treasury")
@RequiredArgsConstructor
public class TreasuryController {

    private final TreasuryService treasuryService;
    private final TreasuryRequestService treasuryRequestService;

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

    @PostMapping("/mint")
    @PreAuthorize("hasAuthority('FLOAT_MANAGE')")
    public ResponseEntity<ApiResponse<TreasuryRequestResponse>> requestMint(
            @Valid @RequestBody MintRequest request, Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        TreasuryRequestResponse response = treasuryRequestService.createStaffRequest(
                principal.userId(), RequestType.MINT, request.amount(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ok("Mint request submitted. Another staff member must approve it.", response));
    }

    @PostMapping("/commission/topup")
    @PreAuthorize("hasAuthority('COMMISSION_MANAGE')")
    public ResponseEntity<ApiResponse<TreasuryRequestResponse>> requestCommissionTopup(
            @Valid @RequestBody CommissionTopupRequest request, Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        TreasuryRequestResponse response = treasuryRequestService.createStaffRequest(
                principal.userId(), RequestType.COMMISSION_TOPUP, request.amount(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ok("Commission top-up request submitted. Another staff member must approve it.", response));
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
