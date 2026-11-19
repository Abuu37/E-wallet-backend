package com.application.e_wallet.customer.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CustomerProfileResponse(
        UUID customerId,
        String username,
        String fullName,
        String email,
        String phoneNumber,
        String nida,
        LocalDate dateOfBirth,
        CustomerWalletSummary wallet,
        CustomerTransactionSummary transaction,
        OffsetDateTime createdAt
) {
}
