package com.application.e_wallet.role.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record UpdateRolePermissionsRequest(
        @NotNull(message = "Permissions are required")
        Set<String> permissions,

        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        String reason
) {
}
