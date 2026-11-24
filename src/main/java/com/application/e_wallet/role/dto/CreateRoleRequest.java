package com.application.e_wallet.role.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRoleRequest(
        @NotBlank(message = "Role name is required")
        @Size(max = 50, message = "Role name cannot exceed 50 characters")
        String name,

        @Size(max = 255, message = "Description cannot exceed 255 characters")
        String description
) {
}
