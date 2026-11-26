package com.application.e_wallet.bank.controller;

import com.application.e_wallet.auth.service.RateLimitingService;
import com.application.e_wallet.bank.dto.BankBalanceResponse;
import com.application.e_wallet.bank.dto.BankExchangeRequest;
import com.application.e_wallet.bank.dto.BankLinkTokenResponse;
import com.application.e_wallet.bank.dto.LinkedBankResponse;
import com.application.e_wallet.bank.service.BankLinkService;
import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.security.jwt.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Link, list, check and remove the signed-in person's own banks. There is never a user id in the URL. */
@RestController
@RequestMapping("/api/v1/banks")
@RequiredArgsConstructor
public class BankController {

    private final BankLinkService bankLinks;
    private final RateLimitingService rateLimitingService;

    @PostMapping("/link-token")
    @PreAuthorize("hasAuthority('CUSTOMER_UPDATE')")
    public ResponseEntity<ApiResponse<BankLinkTokenResponse>> linkToken(Authentication authentication) {
        UUID userId = userId(authentication);
        rateLimitingService.enforceLookup(userId);
        return ok("Bank link session created", new BankLinkTokenResponse(bankLinks.linkToken(userId)));
    }

    @PostMapping("/exchange")
    @PreAuthorize("hasAuthority('CUSTOMER_UPDATE')")
    public ResponseEntity<ApiResponse<LinkedBankResponse>> exchange(@Valid @RequestBody BankExchangeRequest request, Authentication authentication) {
        UUID userId = userId(authentication);
        rateLimitingService.enforceLookup(userId);
        return ok("Bank linked", bankLinks.exchange(userId, request.publicToken(), request.accountId()));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CUSTOMER_VIEW')")
    public ResponseEntity<ApiResponse<List<LinkedBankResponse>>> list(Authentication authentication) {
        return ok("Linked banks retrieved", bankLinks.list(userId(authentication)));
    }

    @GetMapping("/{id}/balance")
    @PreAuthorize("hasAuthority('CUSTOMER_VIEW')")
    public ResponseEntity<ApiResponse<BankBalanceResponse>> balance(@PathVariable UUID id, Authentication authentication) {
        UUID userId = userId(authentication);
        rateLimitingService.enforceLookup(userId);
        return ok("Bank balance retrieved", bankLinks.balance(userId, id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('CUSTOMER_UPDATE')")
    public ResponseEntity<ApiResponse<Void>> unlink(@PathVariable UUID id, Authentication authentication) {
        bankLinks.unlink(userId(authentication), id);
        return ok("Bank removed", null);
    }

    private static UUID userId(Authentication authentication) {
        return ((AuthenticatedUser) authentication.getPrincipal()).userId();
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(String message, T data) {
        return ResponseEntity.ok(ApiResponse.<T>builder().timestamp(Instant.now()).status(HttpStatus.OK.value()).message(message).data(data).build());
    }
}
