package com.application.e_wallet.staff.dto;

import java.util.UUID;

public record StaffOnboardResponse(
        UUID staffId,
        String staffNo,
        UUID userId,
        String firstName,
        String middleName,
        String lastName,
        String email,
        String phoneNumber,
        String jobTitle,
        String status
) {
}
