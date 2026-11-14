package com.application.e_wallet.treasury.dto;

import jakarta.validation.constraints.NotBlank;

// Body for rejecting a treasury request; approval takes no body.
public record TreasuryRequestDecisionRequest(

        @NotBlank
        String reason

) {
}
