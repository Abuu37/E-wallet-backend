package com.application.e_wallet.treasury.operations;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OperationDecisionRequest(@NotBlank(message = "Say why") @Size(max = 500) String reason) {
}
