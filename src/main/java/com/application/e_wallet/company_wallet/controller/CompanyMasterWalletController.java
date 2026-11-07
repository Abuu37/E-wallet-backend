package com.application.e_wallet.company_wallet.controller;

import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.company_wallet.dto.AgentWithdrawRequest;
import com.application.e_wallet.company_wallet.dto.AllocateFloatRequest;
import com.application.e_wallet.company_wallet.dto.CompanyMasterWalletResponse;
import com.application.e_wallet.company_wallet.dto.MintFloatRequest;
import com.application.e_wallet.company_wallet.dto.TransactionJournalResponse;
import com.application.e_wallet.company_wallet.service.CompanyMasterWalletService;
import com.application.e_wallet.security.jwt.AuthenticatedUser;
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

@RestController
@RequestMapping("/api/v1/company-wallet")
@RequiredArgsConstructor
public class CompanyMasterWalletController {

    private final CompanyMasterWalletService companyMasterWalletService;

    @GetMapping
    @PreAuthorize("hasAuthority('COMPANY_WALLET_VIEW')")
    public ResponseEntity<ApiResponse<CompanyMasterWalletResponse>> getWallet() {
        CompanyMasterWalletResponse response = companyMasterWalletService.getWallet();

        ApiResponse<CompanyMasterWalletResponse> body = ApiResponse.<CompanyMasterWalletResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Company master wallet retrieved successfully")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    @GetMapping("/journal")
    @PreAuthorize("hasAuthority('LEDGER_VIEW')")
    public ResponseEntity<ApiResponse<List<TransactionJournalResponse>>> getJournal() {
        List<TransactionJournalResponse> response = companyMasterWalletService.getJournalHistory();

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
    public ResponseEntity<ApiResponse<CompanyMasterWalletResponse>> mint(
            @Valid @RequestBody MintFloatRequest request, Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        UUID adminUserId = principal.userId();

        CompanyMasterWalletResponse response = companyMasterWalletService.mint(request, adminUserId);

        ApiResponse<CompanyMasterWalletResponse> body = ApiResponse.<CompanyMasterWalletResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Float minted successfully")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    @PostMapping("/allocate")
    @PreAuthorize("hasAuthority('FLOAT_MANAGE')")
    public ResponseEntity<ApiResponse<CompanyMasterWalletResponse>> allocate(
            @Valid @RequestBody AllocateFloatRequest request, Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        UUID adminUserId = principal.userId();

        CompanyMasterWalletResponse response = companyMasterWalletService.allocate(request, adminUserId);

        ApiResponse<CompanyMasterWalletResponse> body = ApiResponse.<CompanyMasterWalletResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Float allocated successfully")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    @PostMapping("/withdraw")
    @PreAuthorize("hasAuthority('FLOAT_MANAGE')")
    public ResponseEntity<ApiResponse<CompanyMasterWalletResponse>> withdraw(
            @Valid @RequestBody AgentWithdrawRequest request, Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        UUID adminUserId = principal.userId();

        CompanyMasterWalletResponse response = companyMasterWalletService.withdraw(request, adminUserId);

        ApiResponse<CompanyMasterWalletResponse> body = ApiResponse.<CompanyMasterWalletResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Float withdrawn successfully")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }
}
