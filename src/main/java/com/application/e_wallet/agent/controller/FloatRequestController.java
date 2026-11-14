package com.application.e_wallet.agent.controller;

import com.application.e_wallet.agent.dto.FloatRequestCreateRequest;
import com.application.e_wallet.agent.dto.FloatRequestDecisionRequest;
import com.application.e_wallet.agent.dto.FloatRequestResponse;
import com.application.e_wallet.agent.service.AgentFloatRequestService;
import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.security.jwt.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@RestController
@RequestMapping("/api/v1/float-requests")
@RequiredArgsConstructor
public class FloatRequestController {

    private static final String MANAGE_AUTHORITY = "FLOAT_MANAGE";
    private final AgentFloatRequestService floatRequestService;

    @PostMapping
    @PreAuthorize("hasAuthority('REQUEST_FLOAT')")
    public ResponseEntity<ApiResponse<?>> createRequest(
            @Valid @RequestBody FloatRequestCreateRequest request,
            Authentication authentication
    ) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        log.info("==========user id: {}==========", principal.userId());
        FloatRequestResponse response = floatRequestService.createRequest(principal.userId(), request);

        ApiResponse<?> body = ApiResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.CREATED.value())
                .message("Float request submitted successfully. Awaiting admin review")
                .data(response)
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('FLOAT_VIEW')")
    public ResponseEntity<ApiResponse<List<FloatRequestResponse>>> listRequests(Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        List<FloatRequestResponse> response = floatRequestService.listRequests(principal.userId(), canManage(authentication));

        ApiResponse<List<FloatRequestResponse>> body = ApiResponse.<List<FloatRequestResponse>>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Float requests retrieved successfully.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    @GetMapping("/{requestId}")
    @PreAuthorize("hasAuthority('FLOAT_VIEW')")
    public ResponseEntity<ApiResponse<FloatRequestResponse>> getRequest(
            @PathVariable UUID requestId,
            Authentication authentication
    ) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        FloatRequestResponse response = floatRequestService.getRequest(requestId, principal.userId(), canManage(authentication));

        ApiResponse<FloatRequestResponse> body = ApiResponse.<FloatRequestResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Float request retrieved successfully.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    @PostMapping("/{requestId}/approve")
    @PreAuthorize("hasAuthority('FLOAT_MANAGE')")
    public ResponseEntity<ApiResponse<FloatRequestResponse>> approveRequest(
            @PathVariable UUID requestId,
            Authentication authentication
    ) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        FloatRequestResponse response = floatRequestService.approveRequest(requestId, principal.userId());

        ApiResponse<FloatRequestResponse> body = ApiResponse.<FloatRequestResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Float request approved and float topped up.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    @PostMapping("/{requestId}/reject")
    @PreAuthorize("hasAuthority('FLOAT_MANAGE')")
    public ResponseEntity<ApiResponse<FloatRequestResponse>> rejectRequest(
            @PathVariable UUID requestId,
            @Valid @RequestBody FloatRequestDecisionRequest request,
            Authentication authentication
    ) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        FloatRequestResponse response = floatRequestService.rejectRequest(requestId, principal.userId(), request);

        ApiResponse<FloatRequestResponse> body = ApiResponse.<FloatRequestResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Float request rejected.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    private boolean canManage(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> MANAGE_AUTHORITY.equals(authority.getAuthority()));
    }
}
