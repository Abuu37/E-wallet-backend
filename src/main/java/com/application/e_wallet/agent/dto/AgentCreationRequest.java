
package com.application.e_wallet.agent.dto;

import com.application.e_wallet.agent.entity.Gender;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;

import java.time.LocalDate;

public record AgentCreationRequest(


        @NotBlank
        String firstName,

        @Size(max = 100, message = "Middle name cannot exceed 100 characters")
        String middleName,

        @NotBlank
        String lastName,

        @NotBlank
        @Email
        String email,

        @NotBlank
        String phone,

        @NotNull
        Gender gender,

        @NotNull
        @Past
        LocalDate dateOfBirth,

        @NotBlank
        String regionId,

        @NotBlank
        String districtId,

        @NotBlank
        String businessName,

        @NotBlank
        String tinNumber

) {
}
