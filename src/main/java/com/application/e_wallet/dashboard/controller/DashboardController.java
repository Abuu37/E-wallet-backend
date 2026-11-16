package com.application.e_wallet.dashboard.controller;

import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.dashboard.dto.DashboardOverviewResponse;
import com.application.e_wallet.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
    public ResponseEntity<ApiResponse<DashboardOverviewResponse>> getOverview() {
        DashboardOverviewResponse response = dashboardService.getOverview();

        ApiResponse<DashboardOverviewResponse> body = ApiResponse.<DashboardOverviewResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Dashboard overview retrieved successfully")
                .data(response)
                .build();

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(body);
    }
}
