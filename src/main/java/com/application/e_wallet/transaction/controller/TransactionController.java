package com.application.e_wallet.transaction.controller;

import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.security.jwt.AuthenticatedUser;
import com.application.e_wallet.transaction.dto.CashInRequest;
import com.application.e_wallet.transaction.dto.CashInResponse;
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
import com.application.e_wallet.transaction.dto.TransferRequest;
import com.application.e_wallet.transaction.dto.TransferResponse;
import com.application.e_wallet.transaction.dto.WithdrawRequest;
import com.application.e_wallet.transaction.dto.WithdrawResponse;
import com.application.e_wallet.transaction.dto.MyTransactionList;
import com.application.e_wallet.transaction.service.CustomerMoneyService;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;
    private final CustomerMoneyService customerMoneyService;
    private final com.application.e_wallet.auth.service.RateLimitingService rateLimitingService;
    private final com.application.e_wallet.common.idempotency.IdempotencyService idempotencyService;

    @PostMapping("/cash-in")
    @PreAuthorize("hasAuthority('TOPUP_CREATE')")
    public ResponseEntity<ApiResponse<CashInResponse>> cashIn(@Valid @RequestBody CashInRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();

        CashInResponse response = idempotencyService.execute(principal.userId(), idempotencyKey, "POST /transactions/cash-in", request,
                CashInResponse.class, () -> transactionService.cashIn(request, principal.userId()));

        ApiResponse<CashInResponse> body = ApiResponse.<CashInResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Cash-in completed successfully.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    /** Customer sends to another WalletPesa wallet by phone, approving with their own PIN. */
    @PostMapping("/transfer")
    @PreAuthorize("hasAuthority('WALLET_TO_WALLET_TRANSFER')")
    public ResponseEntity<ApiResponse<TransferResponse>> transfer(@Valid @RequestBody TransferRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        rateLimitingService.enforceMoney(principal.userId());

        TransferResponse response = idempotencyService.execute(principal.userId(), idempotencyKey, "POST /transactions/transfer", request,
                TransferResponse.class, () -> customerMoneyService.transfer(request, principal.userId()));

        return ResponseEntity.ok(ApiResponse.<TransferResponse>builder()
                .timestamp(Instant.now()).status(HttpStatus.OK.value()).message("Money sent.").data(response).build());
    }

    /** Customer withdraws cash at an agent: the agent's till number, the amount and the customer's own PIN. The agent never sees the PIN. */
    @PostMapping("/withdraw")
    @PreAuthorize("hasAuthority('WITHDRAW_REQUEST')")
    public ResponseEntity<ApiResponse<WithdrawResponse>> withdraw(@Valid @RequestBody WithdrawRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        rateLimitingService.enforceMoney(principal.userId());

        WithdrawResponse response = idempotencyService.execute(principal.userId(), idempotencyKey, "POST /transactions/withdraw", request,
                WithdrawResponse.class, () -> customerMoneyService.withdraw(request, principal.userId()));

        return ResponseEntity.ok(ApiResponse.<WithdrawResponse>builder()
                .timestamp(Instant.now()).status(HttpStatus.OK.value()).message("Withdrawal approved. Show the code to the agent.").data(response).build());
    }

    /** My transactions: filter by type and date, search, page. */
    @GetMapping("/me")
    @PreAuthorize("hasAnyAuthority('TRANSACTION_VIEW_OWN', 'TRANSACTION_VIEW_ALL')")
    public ResponseEntity<ApiResponse<MyTransactionList>> mine(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String type,
            @org.springframework.web.bind.annotation.RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @org.springframework.web.bind.annotation.RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String q,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        MyTransactionList list = transactionService.listMine(principal.userId(), type, from, to, q,
                org.springframework.data.domain.PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
        return ResponseEntity.ok(ApiResponse.<MyTransactionList>builder()
                .timestamp(Instant.now()).status(HttpStatus.OK.value()).message("Transactions retrieved.").data(list).build());
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
