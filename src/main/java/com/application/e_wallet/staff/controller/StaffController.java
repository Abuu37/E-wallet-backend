package com.application.e_wallet.staff.controller;

import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.security.jwt.AuthenticatedUser;
import com.application.e_wallet.staff.dto.StaffCreationRequest;
import com.application.e_wallet.staff.dto.StaffDetailResponse;
import com.application.e_wallet.staff.dto.StaffOnboardResponse;
import com.application.e_wallet.staff.dto.StaffSummaryResponse;
import com.application.e_wallet.staff.dto.StaffUpdateRequest;
import com.application.e_wallet.staff.dto.StaffUpdateResponse;
import com.application.e_wallet.staff.service.StaffService;
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
@RequestMapping("/api/v1/staff")
@RequiredArgsConstructor
public class StaffController {

    private final StaffService staffService;

    @PostMapping("/onboard")
    @PreAuthorize("hasAuthority('USER_CREATE')")
    public ResponseEntity<ApiResponse<StaffOnboardResponse>> onboardStaff(
            @Valid @RequestBody StaffCreationRequest request, Authentication authentication
    ) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        StaffOnboardResponse response = staffService.createStaff(principal.userId(), request);

        ApiResponse<StaffOnboardResponse> body = ApiResponse.<StaffOnboardResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.CREATED.value())
                .message("Staff member onboarded successfully. A one-time PIN has been sent to the email.")
                .data(response)
                .build();

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(body);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('USER_VIEW')")
    public ResponseEntity<ApiResponse<List<StaffSummaryResponse>>> listStaff() {
        List<StaffSummaryResponse> response = staffService.listStaff();

        ApiResponse<List<StaffSummaryResponse>> body = ApiResponse.<List<StaffSummaryResponse>>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Staff retrieved successfully.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    /** The signed-in staff member. Declared before the id route so "profile" is never read as an id. */
    @GetMapping("/profile")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<com.application.e_wallet.staff.dto.StaffProfileResponse>> profile(Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.<com.application.e_wallet.staff.dto.StaffProfileResponse>builder()
                .timestamp(Instant.now()).status(HttpStatus.OK.value()).message("Profile retrieved.").data(staffService.profile(principal.userId())).build());
    }

    @GetMapping("/{staffId}")
    @PreAuthorize("hasAuthority('USER_VIEW')")
    public ResponseEntity<ApiResponse<StaffDetailResponse>> getStaff(@PathVariable UUID staffId) {
        StaffDetailResponse response = staffService.getStaff(staffId);

        ApiResponse<StaffDetailResponse> body = ApiResponse.<StaffDetailResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Staff member retrieved successfully.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    @PutMapping("/{staffId}")
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    public ResponseEntity<ApiResponse<StaffUpdateResponse>> updateStaff(
            @PathVariable UUID staffId,
            @Valid @RequestBody StaffUpdateRequest request
    ) {
        StaffUpdateResponse response = staffService.updateStaff(staffId, request);

        ApiResponse<StaffUpdateResponse> body = ApiResponse.<StaffUpdateResponse>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Staff member updated successfully.")
                .data(response)
                .build();

        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/{staffId}")
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    public ResponseEntity<ApiResponse<Void>> deactivateStaff(@PathVariable UUID staffId) {
        staffService.deactivateStaff(staffId);

        ApiResponse<Void> body = ApiResponse.<Void>builder()
                .timestamp(Instant.now())
                .status(HttpStatus.OK.value())
                .message("Staff member deactivated successfully.")
                .build();

        return ResponseEntity.ok(body);
    }

    /** Staff have no NIDA on file, so a forgotten PIN is reset by an administrator. */
    @PostMapping("/{staffId}/reset-pin")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> resetPin(@PathVariable UUID staffId) {
        staffService.resetPin(staffId);
        return ResponseEntity.noContent().build();
    }
}
