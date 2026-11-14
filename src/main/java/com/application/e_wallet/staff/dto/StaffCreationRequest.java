package com.application.e_wallet.staff.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record StaffCreationRequest(

        @NotBlank
        String username,

        @NotBlank
        String firstName,

        String middleName,

        @NotBlank
        String lastName,

        @NotBlank
        @Email
        String email,

        @NotBlank
        String phone,

        @NotBlank
        String jobTitle

) {
}
