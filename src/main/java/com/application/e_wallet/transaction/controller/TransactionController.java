package com.application.e_wallet.transaction.controller;

import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.security.jwt.AuthenticatedUser;
import com.application.e_wallet.transaction.dto.CashInRequest;
import com.application.e_wallet.transaction.dto.CashInResponse;
import com.application.e_wallet.transaction.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping("/cash-in")
    @PreAuthorize("hasAuthority('TOPUP_CREATE')")
    public ResponseEntity<ApiResponse<CashInResponse>> cashIn(@Valid @RequestBody CashInRequest request, Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();

        CashInResponse response = transactionService.cashIn(request, principal.userId());

        ApiResponse<CashInResponse> body = ApiResponse.<CashInResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Cash-in completed successfully.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }
}
