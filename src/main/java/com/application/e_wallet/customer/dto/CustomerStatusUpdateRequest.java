package com.application.e_wallet.customer.dto;

import com.application.e_wallet.customer.entity.CustomerStatus;
import jakarta.validation.constraints.NotNull;

public record CustomerStatusUpdateRequest(
        @NotNull
        CustomerStatus status
) {
}
