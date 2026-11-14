package com.application.e_wallet.agent.controller;

import com.application.e_wallet.agent.dto.AgentCreationRequest;
import com.application.e_wallet.agent.dto.AgentResponse;
import com.application.e_wallet.agent.dto.AgentUpdateRequest;
import com.application.e_wallet.agent.dto.FloatTopupRequest;
import com.application.e_wallet.agent.dto.FloatWithdrawRequest;
import com.application.e_wallet.agent.service.AgentService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    @PostMapping("/onboard")
    @PreAuthorize("hasAuthority('USER_CREATE')")
    public ResponseEntity<ApiResponse<AgentResponse>> onboardAgent(
            @Valid @RequestBody AgentCreationRequest request
    ) {
        AgentResponse response = agentService.createAgent(request);

        ApiResponse<AgentResponse> body = ApiResponse.<AgentResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.CREATED.value())
                .message("Agent onboarded successfully. A one-time PIN has been to the email.")
                .data(response)
                .build();

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(body);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('USER_VIEW')")
    public ResponseEntity<ApiResponse<List<AgentResponse>>> listAgents() {
        List<AgentResponse> response = agentService.listAgents();

        ApiResponse<List<AgentResponse>> body = ApiResponse.<List<AgentResponse>>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Agents retrieved successfully.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    @GetMapping("/{agentId}")
    @PreAuthorize("hasAuthority('USER_VIEW')")
    public ResponseEntity<ApiResponse<AgentResponse>> listAgent(@PathVariable UUID agentId) {
        AgentResponse response = agentService.listAgent(agentId);

        ApiResponse<AgentResponse> body = ApiResponse.<AgentResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Agent retrieved successfully.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    @PutMapping("/{agentId}")
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    public ResponseEntity<ApiResponse<AgentResponse>> updateAgent(
            @PathVariable UUID agentId,
            @Valid @RequestBody AgentUpdateRequest request
    ) {
        AgentResponse response = agentService.updateAgent(agentId, request);

        ApiResponse<AgentResponse> body = ApiResponse.<AgentResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Agent updated successfully.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/{agentId}")
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    public ResponseEntity<ApiResponse<Void>> deactivateAgent(@PathVariable UUID agentId) {
        agentService.deactivateAgent(agentId);

        ApiResponse<Void> body = ApiResponse.<Void>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Agent deactivated successfully.")
                .build();

        return ResponseEntity.ok(body);
    }

    @PostMapping("/{agentId}/float/topup")
    @PreAuthorize("hasAuthority('FLOAT_MANAGE')")
    public ResponseEntity<ApiResponse<AgentResponse>> topupFloat(
            @PathVariable UUID agentId,
            @Valid @RequestBody FloatTopupRequest request,
            Authentication authentication
    ) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        UUID adminUserId = principal.userId();

        AgentResponse response = agentService.topupFloat(agentId, request, adminUserId);

        ApiResponse<AgentResponse> body = ApiResponse.<AgentResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Float topped up successfully.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    @PostMapping("/{agentId}/float/withdraw")
    @PreAuthorize("hasAuthority('FLOAT_MANAGE')")
    public ResponseEntity<ApiResponse<AgentResponse>> withdrawFloat(@PathVariable UUID agentId, @Valid @RequestBody FloatWithdrawRequest request, Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        UUID adminUserId = principal.userId();

        AgentResponse response = agentService.withdrawFloat(agentId, request, adminUserId);

        ApiResponse<AgentResponse> body = ApiResponse.<AgentResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Float withdrawn successfully.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }
}
