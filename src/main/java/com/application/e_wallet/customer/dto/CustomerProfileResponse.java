package com.application.e_wallet.customer.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record CustomerProfileResponse(
        UUID customerId,
        String username,
        String fullName,
        String email,
        String phoneNumber,
        String nida,
        LocalDate dateOfBirth,
        String status,
        CustomerTransactionSummary summary,
        CustomerWalletSummary wallet,
        List<CustomerTransactionItem> transactions,
        OffsetDateTime createdAt
) {
}
