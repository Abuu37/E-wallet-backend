package com.application.e_wallet.agent.controller;

import com.application.e_wallet.agent.dto.AgentCreationRequest;
import com.application.e_wallet.agent.dto.AgentCreationResponse;
import com.application.e_wallet.agent.service.AgentService;
import com.application.e_wallet.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    @PostMapping("/onboard")
    @PreAuthorize("hasAuthority('USER_CREATE')")
    public ResponseEntity<ApiResponse<AgentCreationResponse>> onboardAgent(
            @Valid @RequestBody AgentCreationRequest request
    ) {
        AgentCreationResponse response = agentService.createAgent(request);

        ApiResponse<AgentCreationResponse> body = ApiResponse.<AgentCreationResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.CREATED.value())
                .message("Agent onboarded successfully. A one-time PIN has been to the email.")
                .data(response)
                .build();

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(body);
    }
}
