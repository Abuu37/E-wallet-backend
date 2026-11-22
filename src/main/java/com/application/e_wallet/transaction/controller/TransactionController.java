package com.application.e_wallet.transaction.controller;

import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.security.jwt.AuthenticatedUser;
import com.application.e_wallet.transaction.dto.CashInRequest;
import com.application.e_wallet.transaction.dto.CashInResponse;
import com.application.e_wallet.transaction.dto.CashOutRequest;
import com.application.e_wallet.transaction.dto.CashOutResponse;
import com.application.e_wallet.transaction.dto.TransactionDetailResponse;
import com.application.e_wallet.transaction.dto.TransactionListResponse;
import com.application.e_wallet.transaction.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @PostMapping("/cash-out")
    @PreAuthorize("hasAuthority('WITHDRAWAL_CREATE')")
    public ResponseEntity<ApiResponse<CashOutResponse>> cashOut(@Valid @RequestBody CashOutRequest request, Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();

        CashOutResponse response = transactionService.cashOut(request, principal.userId());

        ApiResponse<CashOutResponse> body = ApiResponse.<CashOutResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Cash-out completed successfully.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('TRANSACTION_VIEW_ALL', 'TRANSACTION_VIEW_OWN')")
    public ResponseEntity<ApiResponse<TransactionListResponse>> listTransactions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        TransactionListResponse response = transactionService.listTransactions(
                principal.userId(), hasAuthority(authentication, "TRANSACTION_VIEW_ALL"), pageable);

        ApiResponse<TransactionListResponse> body = ApiResponse.<TransactionListResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Transactions retrieved successfully.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    @GetMapping("/{referenceNo}")
    @PreAuthorize("hasAnyAuthority('TRANSACTION_VIEW_ALL', 'TRANSACTION_VIEW_DETAILS')")
    public ResponseEntity<ApiResponse<TransactionDetailResponse>> getTransactionDetail(
            @PathVariable String referenceNo, Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();

        TransactionDetailResponse response = transactionService.getTransactionDetail(
                principal.userId(), hasAuthority(authentication, "TRANSACTION_VIEW_ALL"), referenceNo);

        ApiResponse<TransactionDetailResponse> body = ApiResponse.<TransactionDetailResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Transaction retrieved successfully.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    private static boolean hasAuthority(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }
}
