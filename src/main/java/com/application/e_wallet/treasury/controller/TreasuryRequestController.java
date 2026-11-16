package com.application.e_wallet.treasury.controller;

import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.security.jwt.AuthenticatedUser;
import com.application.e_wallet.treasury.dto.TreasuryRequestCreateRequest;
import com.application.e_wallet.treasury.dto.TreasuryRequestDecisionRequest;
import com.application.e_wallet.treasury.dto.TreasuryRequestResponse;
import com.application.e_wallet.treasury.service.TreasuryRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/treasury/requests")
@RequiredArgsConstructor
public class TreasuryRequestController {

    private static final String MANAGE_AUTHORITY = "FLOAT_MANAGE";
    private final TreasuryRequestService treasuryRequestService;

    @PostMapping
    @PreAuthorize("hasAuthority('REQUEST_FLOAT')")
    public ResponseEntity<ApiResponse<TreasuryRequestResponse>> createRequest(
            @Valid @RequestBody TreasuryRequestCreateRequest request,
            Authentication authentication
    ) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        TreasuryRequestResponse response = treasuryRequestService.createRequest(principal.userId(), request);

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
    public ResponseEntity<ApiResponse<List<TreasuryRequestResponse>>> listRequests(Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        List<TreasuryRequestResponse> response = treasuryRequestService.listRequests(principal.userId(), canManage(authentication));

        ApiResponse<List<TreasuryRequestResponse>> body = ApiResponse.<List<TreasuryRequestResponse>>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Float requests retrieved successfully.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    @GetMapping("/{requestId}")
    @PreAuthorize("hasAuthority('FLOAT_VIEW')")
    public ResponseEntity<ApiResponse<TreasuryRequestResponse>> getRequest(
            @PathVariable UUID requestId,
            Authentication authentication
    ) {
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
            Authentication authentication
    ) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        TreasuryRequestResponse response = treasuryRequestService.approveRequest(requestId, principal.userId());

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
            Authentication authentication
    ) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        TreasuryRequestResponse response = treasuryRequestService.rejectRequest(requestId, principal.userId(), request);

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
