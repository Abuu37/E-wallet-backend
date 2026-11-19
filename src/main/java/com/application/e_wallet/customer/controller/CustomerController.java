package com.application.e_wallet.customer.controller;

import com.application.e_wallet.auth.dto.CustomerRegistrationRequest;
import com.application.e_wallet.auth.dto.RegistrationResponse;
import com.application.e_wallet.auth.service.RateLimitingService;
import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.common.exception.TooManyRequestsException;
import com.application.e_wallet.customer.dto.CustomerListResponse;
import com.application.e_wallet.customer.dto.CustomerProfileResponse;
import com.application.e_wallet.customer.entity.CustomerStatus;
import com.application.e_wallet.customer.service.CustomerService;
import com.application.e_wallet.security.jwt.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;
    private final RateLimitingService rateLimitingService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegistrationResponse>> registerCustomer(@Valid @RequestBody CustomerRegistrationRequest request, HttpServletRequest httpRequest) {

        String ip = httpRequest.getRemoteAddr();

        if (!rateLimitingService.tryConsumeIp(ip) || !rateLimitingService.tryConsumeAccount(request.getEmail())) {
            throw new TooManyRequestsException("Too many registration attempts. Please try again later.");
        }

        RegistrationResponse response = customerService.registerCustomer(request);

        ApiResponse<RegistrationResponse> body = ApiResponse.<RegistrationResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.CREATED.value())
                .message("OTP has been sent to your email.")
                .data(response)
                .build();

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(body);
    }

    // Self-service: a customer viewing their OWN profile. CUSTOMER_VIEW is held by the
    // CUSTOMER role itself - admin's wider view below is deliberately guarded by a
    // different permission (USER_VIEW), so a customer can't reuse this authority to
    // browse other customers via the endpoints below.
    @GetMapping("/profile")
    @PreAuthorize("hasAuthority('CUSTOMER_VIEW')")
    public ResponseEntity<ApiResponse<CustomerProfileResponse>> getOwnProfile(Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        CustomerProfileResponse response = customerService.getOwnProfile(principal.userId());

        ApiResponse<CustomerProfileResponse> body = ApiResponse.<CustomerProfileResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Customer profile retrieved successfully.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('USER_VIEW')")
    public ResponseEntity<ApiResponse<CustomerListResponse>> listCustomers(
            @RequestParam(required = false) CustomerStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        CustomerListResponse response = customerService.listCustomers(pageable, status);

        ApiResponse<CustomerListResponse> body = ApiResponse.<CustomerListResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Customers retrieved successfully.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    @GetMapping("/{customerId}")
    @PreAuthorize("hasAuthority('USER_VIEW')")
    public ResponseEntity<ApiResponse<CustomerProfileResponse>> getCustomer(@PathVariable UUID customerId) {
        CustomerProfileResponse response = customerService.getCustomer(customerId);

        ApiResponse<CustomerProfileResponse> body = ApiResponse.<CustomerProfileResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Customer retrieved successfully.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }
}
