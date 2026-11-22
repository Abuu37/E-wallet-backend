package com.application.e_wallet.dashboard.admin.controller;

import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.dashboard.admin.dto.DashboardOverviewResponse;
import com.application.e_wallet.dashboard.admin.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
    public ResponseEntity<ApiResponse<DashboardOverviewResponse>> getOverview(
            @RequestParam(defaultValue = "today") String range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        DashboardOverviewResponse response = dashboardService.getOverview(range, from, to);

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
