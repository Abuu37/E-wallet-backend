package com.application.e_wallet.staff.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record StaffDetailResponse(
        UUID staffId,
        String staffNo,
        UUID userId,
        String firstName,
        String middleName,
        String lastName,
        String email,
        String phoneNumber,
        String jobTitle,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
