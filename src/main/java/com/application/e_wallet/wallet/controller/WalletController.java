package com.application.e_wallet.wallet.controller;

import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.security.jwt.AuthenticatedUser;
import com.application.e_wallet.wallet.dto.WalletResponse;
import com.application.e_wallet.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('WALLET_VIEW')")
    public ResponseEntity<ApiResponse<WalletResponse>> getMyWallet(Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();

        WalletResponse response = walletService.getMyWallet(principal.userId());

        ApiResponse<WalletResponse> body = ApiResponse.<WalletResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Wallet retrieved successfully")
                .data(response)
                .build();

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(body);
    }

    @GetMapping("/{walletNumber}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<WalletResponse>> getWalletByNumber(@PathVariable String walletNumber) {
        WalletResponse response = walletService.getWalletByNumber(walletNumber);

        ApiResponse<WalletResponse> body = ApiResponse.<WalletResponse>builder()
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
