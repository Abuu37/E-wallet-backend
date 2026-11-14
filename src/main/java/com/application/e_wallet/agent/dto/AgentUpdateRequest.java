package com.application.e_wallet.agent.dto;

import com.application.e_wallet.agent.entity.Gender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;

import java.time.LocalDate;

// Full replace (PUT) of an agent's editable profile fields. Email, phone,
// and agentNumber are identity-critical and are not editable here.
public record AgentUpdateRequest(

        @NotBlank
        String firstName,

        @NotBlank
        String middleName,

        @NotBlank
        String lastName,

        @NotNull
        Gender gender,

        @NotNull
        @Past
        LocalDate dateOfBirth,

        @NotBlank
        String regionId,

        @NotBlank
        String districtId

) {
}
