package com.application.e_wallet.staff.dto;

import jakarta.validation.constraints.NotBlank;

// Full replace (PUT) of a staff member's editable profile fields. Email, phone,
// username, and staffNo are identity-critical and are not editable here - same
// convention as AgentUpdateRequest.
public record StaffUpdateRequest(

        @NotBlank
        String firstName,

        String middleName,

        @NotBlank
        String lastName,

        @NotBlank
        String jobTitle

) {
}
