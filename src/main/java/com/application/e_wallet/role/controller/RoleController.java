package com.application.e_wallet.role.controller;

import com.application.e_wallet.auth.service.RateLimitingService;
import com.application.e_wallet.common.dto.ApiResponse;
import com.application.e_wallet.role.dto.CreateRoleRequest;
import com.application.e_wallet.role.dto.RoleItem;
import com.application.e_wallet.role.dto.RoleListResponse;
import com.application.e_wallet.role.dto.UpdateRolePermissionsRequest;
import com.application.e_wallet.role.service.RoleService;
import com.application.e_wallet.security.jwt.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;
    private final RateLimitingService rateLimitingService;

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_VIEW')")
    public ResponseEntity<ApiResponse<RoleListResponse>> list() {
        return ResponseEntity.ok(ok("Roles retrieved successfully", roleService.listRoles()));
    }

    @PutMapping("/{roleId}/permissions")
    @PreAuthorize("hasAuthority('ROLE_ASSIGN')")
    public ResponseEntity<ApiResponse<RoleItem>> updatePermissions(
            @PathVariable UUID roleId, @Valid @RequestBody UpdateRolePermissionsRequest request,
            Authentication authentication, HttpServletRequest http) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        rateLimitingService.enforce(http.getRemoteAddr(), "roles:" + principal.userId());
        RoleItem item = roleService.updatePermissions(principal.userId(), roleId, request.permissions(), request.reason(), http.getRemoteAddr());
        return ResponseEntity.ok(ok("Role permissions updated. Signed-in users receive the change within 15 minutes.", item));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_ASSIGN')")
    public ResponseEntity<ApiResponse<RoleItem>> create(
            @Valid @RequestBody CreateRoleRequest request, Authentication authentication, HttpServletRequest http) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        rateLimitingService.enforce(http.getRemoteAddr(), "roles:" + principal.userId());
        RoleItem item = roleService.createRole(principal.userId(), request.name(), request.description(), http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(ok("Role created", item));
    }

    private static <T> ApiResponse<T> ok(String message, T data) {
        return ApiResponse.<T>builder().timestamp(Instant.now()).status(HttpStatus.OK.value()).message(message).data(data).build();
    }
}
