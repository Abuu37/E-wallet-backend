package com.application.e_wallet.agent.dto;

import jakarta.validation.constraints.NotBlank;

// Body for rejecting a float request; approval takes no body.
public record FloatRequestDecisionRequest(

        @NotBlank
        String reason

) {
}
