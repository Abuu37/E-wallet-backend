
package com.application.e_wallet.agent.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record AgentCreationRequest(

        @NotBlank
        String firstName,

        @NotBlank
        String middleName,

        @NotBlank
        String lastName,

        @NotBlank
        @Email
        String email,

        @NotBlank
        String phone

) {
}
