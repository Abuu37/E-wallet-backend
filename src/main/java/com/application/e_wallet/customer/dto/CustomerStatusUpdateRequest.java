package com.application.e_wallet.customer.dto;

import com.application.e_wallet.customer.entity.CustomerStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CustomerStatusUpdateRequest(
        @NotNull
        CustomerStatus status,

        @NotBlank(message = "A reason is required to lock or unlock a customer")
        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        String reason
) {
}
