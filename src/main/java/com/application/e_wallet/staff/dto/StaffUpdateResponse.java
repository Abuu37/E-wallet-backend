package com.application.e_wallet.staff.dto;

import java.util.UUID;

public record StaffUpdateResponse(
        UUID staffId,
        String firstName,
        String middleName,
        String lastName,
        String jobTitle,
        String status
) {
}
