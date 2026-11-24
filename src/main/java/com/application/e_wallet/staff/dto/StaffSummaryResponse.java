package com.application.e_wallet.staff.dto;

import java.util.UUID;

public record StaffSummaryResponse(
        UUID staffId,
        String staffNo,
        String firstName,
        String middleName,
        String lastName,
        String phoneNumber,
        String jobTitle,
        String status,
        String roleName
) {
}
