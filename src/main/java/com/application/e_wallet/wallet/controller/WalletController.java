package com.application.e_wallet.wallet.controller;

import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.security.jwt.AuthenticatedUser;
import com.application.e_wallet.wallet.dto.WalletDetailResponse;
import com.application.e_wallet.wallet.dto.WalletListResponse;
import com.application.e_wallet.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('WALLET_VIEW')")
    public ResponseEntity<ApiResponse<WalletDetailResponse>> getMyWallet(Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();

        WalletDetailResponse response = walletService.getMyWallet(principal.userId());

        ApiResponse<WalletDetailResponse> body = ApiResponse.<WalletDetailResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Wallet retrieved successfully")
                .data(response)
                .build();

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(body);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<WalletListResponse>> listWallets(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        WalletListResponse response = walletService.listWallets(pageable);

        ApiResponse<WalletListResponse> body = ApiResponse.<WalletListResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Wallets retrieved successfully")
                .data(response)
                .build();

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(body);
    }

    @GetMapping("/{accountNumber}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<WalletDetailResponse>> getWalletByAccountNumber(@PathVariable String accountNumber) {
        WalletDetailResponse response = walletService.getWalletByAccountNumber(accountNumber);

        ApiResponse<WalletDetailResponse> body = ApiResponse.<WalletDetailResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Wallet retrieved successfully")
                .data(response)
                .build();

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(body);
    }
}
