package com.application.e_wallet.treasury.controller;

import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.security.jwt.AuthenticatedUser;
import com.application.e_wallet.treasury.dto.TreasuryRequestCreateRequest;
import com.application.e_wallet.treasury.dto.TreasuryRequestDecisionRequest;
import com.application.e_wallet.treasury.dto.TreasuryRequestListResponse;
import com.application.e_wallet.treasury.dto.TreasuryRequestResponse;
import com.application.e_wallet.treasury.entity.RequestStatus;
import com.application.e_wallet.treasury.service.TreasuryRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/treasury/requests")
@RequiredArgsConstructor
public class TreasuryRequestController {

    private static final String MANAGE_AUTHORITY = "FLOAT_MANAGE";
    private final TreasuryRequestService treasuryRequestService;
    private final com.application.e_wallet.common.idempotency.IdempotencyService idempotencyService;

    @PostMapping
    @PreAuthorize("hasAuthority('REQUEST_FLOAT')")
    public ResponseEntity<ApiResponse<TreasuryRequestResponse>> createRequest(@Valid @RequestBody TreasuryRequestCreateRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        TreasuryRequestResponse response = idempotencyService.execute(principal.userId(), idempotencyKey, "POST /treasury/requests", request,
                TreasuryRequestResponse.class, () -> treasuryRequestService.createRequest(principal.userId(), request));

        ApiResponse<TreasuryRequestResponse> body = ApiResponse.<TreasuryRequestResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.CREATED.value())
                .message("Float request submitted successfully. Awaiting admin review")
                .data(response)
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('FLOAT_VIEW')")
    public ResponseEntity<ApiResponse<TreasuryRequestListResponse>> listRequests(
            @RequestParam(required = false) RequestStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication
    ) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        Pageable pageable = PageRequest.of(page, size);
        TreasuryRequestListResponse response = treasuryRequestService.listRequests(principal.userId(), canManage(authentication), status, pageable);

        ApiResponse<TreasuryRequestListResponse> body = ApiResponse.<TreasuryRequestListResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Float requests retrieved successfully.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }


    @GetMapping("/{requestId}")
    @PreAuthorize("hasAuthority('FLOAT_VIEW')")
    public ResponseEntity<ApiResponse<TreasuryRequestResponse>> getRequest(@PathVariable UUID requestId, Authentication authentication) {

        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        TreasuryRequestResponse response = treasuryRequestService.getRequest(requestId, principal.userId(), canManage(authentication));

        ApiResponse<TreasuryRequestResponse> body = ApiResponse.<TreasuryRequestResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Float request retrieved successfully.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    @PostMapping("/{requestId}/approve")
    @PreAuthorize("hasAuthority('FLOAT_MANAGE')")
    public ResponseEntity<ApiResponse<TreasuryRequestResponse>> approveRequest(
            @PathVariable UUID requestId,
            @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication
    ) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        TreasuryRequestResponse response = idempotencyService.execute(principal.userId(), idempotencyKey, "POST /treasury/requests/approve", requestId,
                TreasuryRequestResponse.class, () -> treasuryRequestService.approveRequest(requestId, principal.userId()));

        ApiResponse<TreasuryRequestResponse> body = ApiResponse.<TreasuryRequestResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Float request approved and moved sucessfully to agent account.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    @PostMapping("/{requestId}/reject")
    @PreAuthorize("hasAuthority('FLOAT_MANAGE')")
    public ResponseEntity<ApiResponse<TreasuryRequestResponse>> rejectRequest(
            @PathVariable UUID requestId,
            @Valid @RequestBody TreasuryRequestDecisionRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication
    ) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        TreasuryRequestResponse response = idempotencyService.execute(principal.userId(), idempotencyKey, "POST /treasury/requests/reject", java.util.List.of(requestId, request),
                TreasuryRequestResponse.class, () -> treasuryRequestService.rejectRequest(requestId, principal.userId(), request));

        ApiResponse<TreasuryRequestResponse> body = ApiResponse.<TreasuryRequestResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Treasury request rejected.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    private boolean canManage(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> MANAGE_AUTHORITY.equals(authority.getAuthority()));
    }
}
