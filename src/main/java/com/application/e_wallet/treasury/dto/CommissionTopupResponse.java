package com.application.e_wallet.treasury.dto;

import java.math.BigDecimal;

public record CommissionTopupResponse(
        String transactionReference,
        String transactionType,
        String status,
        String currency,
        BigDecimal amountTransferred,
        WalletMovement sourceAccount,
        WalletMovement destinationAccount,
        String initiatedBy
) {
}
