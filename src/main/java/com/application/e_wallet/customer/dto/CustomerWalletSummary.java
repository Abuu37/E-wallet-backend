package com.application.e_wallet.customer.dto;

import java.math.BigDecimal;

public record CustomerWalletSummary(
        String accountNumber,
        String walletType,
        String currency,
        BigDecimal availableBalance,
        String status
) {
}
