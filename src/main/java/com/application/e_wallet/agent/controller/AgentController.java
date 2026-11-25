package com.application.e_wallet.agent.controller;

import com.application.e_wallet.agent.dto.AgentCreationRequest;
import com.application.e_wallet.agent.dto.AgentDetailResponse;
import com.application.e_wallet.agent.dto.AgentListResponse;
import com.application.e_wallet.agent.dto.AgentOnboardResponse;
import com.application.e_wallet.agent.dto.AgentStatusUpdateRequest;
import com.application.e_wallet.agent.dto.AgentUpdateRequest;
import com.application.e_wallet.agent.dto.AgentUpdateResponse;
import com.application.e_wallet.agent.entity.AgentStatus;
import com.application.e_wallet.agent.service.AgentService;
import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.security.jwt.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    @PostMapping("/onboard")
    @PreAuthorize("hasAuthority('USER_CREATE')")
    public ResponseEntity<ApiResponse<AgentOnboardResponse>> onboardAgent(
            @Valid @RequestBody AgentCreationRequest request, Authentication authentication
    ) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        AgentOnboardResponse response = agentService.createAgent(principal.userId(), request);

        ApiResponse<AgentOnboardResponse> body = ApiResponse.<AgentOnboardResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.CREATED.value())
                .message("Agent onboarded successfully. A one-time PIN has been to the email.")
                .data(response)
                .build();

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(body);
    }

    @GetMapping("/profile")
    @PreAuthorize("hasAuthority('AGENT_VIEW')")
    public ResponseEntity<ApiResponse<AgentDetailResponse>> getOwnProfile(Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        AgentDetailResponse response = agentService.getOwnProfile(principal.userId());

        ApiResponse<AgentDetailResponse> body = ApiResponse.<AgentDetailResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Agent profile retrieved successfully.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('USER_VIEW')")
    public ResponseEntity<ApiResponse<AgentListResponse>> listAgents(
            @RequestParam(required = false) AgentStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        AgentListResponse response = agentService.listAgents(pageable, status);

        ApiResponse<AgentListResponse> body = ApiResponse.<AgentListResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Agents retrieved successfully.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    @GetMapping("/{agentId}")
    @PreAuthorize("hasAuthority('USER_VIEW')")
    public ResponseEntity<ApiResponse<AgentDetailResponse>> listAgent(@PathVariable UUID agentId) {
        AgentDetailResponse response = agentService.listAgent(agentId);

        ApiResponse<AgentDetailResponse> body = ApiResponse.<AgentDetailResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Agent retrieved successfully.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    @PutMapping("/{agentId}")
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    public ResponseEntity<ApiResponse<AgentUpdateResponse>> updateAgent(
            @PathVariable UUID agentId,
            @Valid @RequestBody AgentUpdateRequest request
    ) {
        AgentUpdateResponse response = agentService.updateAgent(agentId, request);

        ApiResponse<AgentUpdateResponse> body = ApiResponse.<AgentUpdateResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Agent updated successfully.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    /** Agents have no NIDA on file, so a forgotten PIN is reset by an administrator. */
    @PostMapping("/{agentId}/reset-pin")
    @PreAuthorize("hasAuthority('AGENT_MANAGE')")
    public ResponseEntity<Void> resetPin(@PathVariable UUID agentId) {
        agentService.resetPin(agentId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{agentId}/status")
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    public ResponseEntity<ApiResponse<AgentUpdateResponse>> updateAgentStatus(
            @PathVariable UUID agentId,
            @Valid @RequestBody AgentStatusUpdateRequest request
    ) {
        AgentUpdateResponse response = agentService.updateAgentStatus(agentId, request);

        ApiResponse<AgentUpdateResponse> body = ApiResponse.<AgentUpdateResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Agent status updated successfully.")
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
}
