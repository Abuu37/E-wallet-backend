package com.application.e_wallet.customer.dto;

import java.util.UUID;

public record CustomerSummaryResponse(
        UUID customerId,
        String fullName,
        String phoneNumber,
        String email,
        String status,
        CustomerWalletSummary wallet
) {
}
