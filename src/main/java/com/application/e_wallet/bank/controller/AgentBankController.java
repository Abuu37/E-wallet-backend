package com.application.e_wallet.bank.controller;

import com.application.e_wallet.auth.service.RateLimitingService;
import com.application.e_wallet.bank.dto.BankMoveRequest;
import com.application.e_wallet.bank.dto.BankMoveResponse;
import com.application.e_wallet.bank.dto.FloatBankSummary;
import com.application.e_wallet.bank.service.AgentBankService;
import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.common.idempotency.IdempotencyService;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.function.Supplier;

/** The agent's own bank: top up float from it, send float or commission to it. Every POST needs the Idempotency-Key header. */
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentBankController {

    private final AgentBankService agentBankService;
    private final IdempotencyService idempotencyService;
    private final RateLimitingService rateLimitingService;

    @GetMapping("/float/bank-summary")
    @PreAuthorize("hasAuthority('AGENT_BANK')")
    public ResponseEntity<ApiResponse<FloatBankSummary>> summary(Authentication authentication) {
        return ok("Bank top-ups today retrieved.", agentBankService.summary(userId(authentication)));
    }

    @PostMapping("/float/bank-topup")
    @PreAuthorize("hasAuthority('AGENT_BANK')")
    public ResponseEntity<ApiResponse<BankMoveResponse>> topUp(@Valid @RequestBody BankMoveRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication authentication) {
        return run("POST /agent/float/bank-topup", "Float topped up from your bank.", request, key, authentication,
                () -> agentBankService.topUpFloat(request, userId(authentication)));
    }

    @PostMapping("/float/bank-withdraw")
    @PreAuthorize("hasAuthority('AGENT_BANK')")
    public ResponseEntity<ApiResponse<BankMoveResponse>> floatToBank(@Valid @RequestBody BankMoveRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication authentication) {
        return run("POST /agent/float/bank-withdraw", "Float sent to your bank.", request, key, authentication,
                () -> agentBankService.floatToBank(request, userId(authentication)));
    }

    @PostMapping("/commission/bank-withdraw")
    @PreAuthorize("hasAuthority('AGENT_BANK')")
    public ResponseEntity<ApiResponse<BankMoveResponse>> commissionToBank(@Valid @RequestBody BankMoveRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication authentication) {
        return run("POST /agent/commission/bank-withdraw", "Commission sent to your bank.", request, key, authentication,
                () -> agentBankService.commissionToBank(request, userId(authentication)));
    }

    private ResponseEntity<ApiResponse<BankMoveResponse>> run(String action, String message, BankMoveRequest request, String key,
            Authentication authentication, Supplier<BankMoveResponse> work) {
        java.util.UUID userId = userId(authentication);
        rateLimitingService.enforceMoney(userId);
        return ok(message, idempotencyService.execute(userId, key, action, request, BankMoveResponse.class, work));
    }

    private static java.util.UUID userId(Authentication authentication) {
        return ((AuthenticatedUser) authentication.getPrincipal()).userId();
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(String message, T data) {
        return ResponseEntity.ok(ApiResponse.<T>builder().timestamp(Instant.now()).status(HttpStatus.OK.value()).message(message).data(data).build());
    }
}
