package com.application.e_wallet.treasury.controller;

import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.security.jwt.AuthenticatedUser;
import com.application.e_wallet.treasury.dto.MintRequest;
import com.application.e_wallet.treasury.dto.TransactionJournalResponse;
import com.application.e_wallet.treasury.dto.TreasuryOverviewResponse;
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
import java.util.UUID;

// Mint is the only float movement this controller exposes. Topup/withdraw only ever happen
// through an approved treasury_requests row (see TreasuryRequestController) - there is no
// direct admin bypass endpoint for them.
@RestController
@RequestMapping("/api/v1/treasury")
@RequiredArgsConstructor
public class TreasuryController {

    private final TreasuryService treasuryService;

    @GetMapping
    @PreAuthorize("hasAuthority('COMPANY_WALLET_VIEW')")
    public ResponseEntity<ApiResponse<TreasuryOverviewResponse>> getOverview() {
        TreasuryOverviewResponse response = treasuryService.getOverview();

        ApiResponse<TreasuryOverviewResponse> body = ApiResponse.<TreasuryOverviewResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Treasury overview retrieved successfully")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    @GetMapping("/journal")
    @PreAuthorize("hasAuthority('LEDGER_VIEW')")
    public ResponseEntity<ApiResponse<List<TransactionJournalResponse>>> getJournal() {
        List<TransactionJournalResponse> response = treasuryService.getJournalHistory();

        ApiResponse<List<TransactionJournalResponse>> body = ApiResponse.<List<TransactionJournalResponse>>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Transaction journal retrieved successfully")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    @PostMapping("/mint")
    @PreAuthorize("hasAuthority('FLOAT_MANAGE')")
    public ResponseEntity<ApiResponse<TreasuryOverviewResponse>> mint(
            @Valid @RequestBody MintRequest request, Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        UUID adminUserId = principal.userId();

        treasuryService.mint(request, adminUserId);
        TreasuryOverviewResponse response = treasuryService.getOverview();

        ApiResponse<TreasuryOverviewResponse> body = ApiResponse.<TreasuryOverviewResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Float minted successfully")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }
}
