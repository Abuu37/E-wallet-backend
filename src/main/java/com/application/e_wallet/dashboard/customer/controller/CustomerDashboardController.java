package com.application.e_wallet.dashboard.customer.controller;

import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.dashboard.customer.dto.CustomerDashboardOverviewResponse;
import com.application.e_wallet.dashboard.customer.service.CustomerDashboardService;
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
@RequestMapping("/api/v1/customer/dashboard")
@RequiredArgsConstructor
public class CustomerDashboardController {

    private final CustomerDashboardService customerDashboardService;

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('WALLET_VIEW')")
    public ResponseEntity<ApiResponse<CustomerDashboardOverviewResponse>> getOverview(Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        CustomerDashboardOverviewResponse response = customerDashboardService.getOverview(principal.userId());

        ApiResponse<CustomerDashboardOverviewResponse> body = ApiResponse.<CustomerDashboardOverviewResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Customer dashboard overview retrieved successfully.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }
}
