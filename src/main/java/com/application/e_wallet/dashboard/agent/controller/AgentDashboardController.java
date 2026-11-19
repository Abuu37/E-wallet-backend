package com.application.e_wallet.dashboard.agent.controller;

import com.application.e_wallet.dashboard.agent.dto.AgentDashboardOverviewResponse;
import com.application.e_wallet.dashboard.agent.service.AgentDashboardService;
import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.security.jwt.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/agent/dashboard")
@RequiredArgsConstructor
public class AgentDashboardController {

    private final AgentDashboardService agentDashboardService;

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('CASHIER_VIEW')")
    public ResponseEntity<ApiResponse<AgentDashboardOverviewResponse>> getOverview(Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        AgentDashboardOverviewResponse response = agentDashboardService.getOverview(principal.userId());

        ApiResponse<AgentDashboardOverviewResponse> body = ApiResponse.<AgentDashboardOverviewResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Agent dashboard overview retrieved successfully")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }
}
