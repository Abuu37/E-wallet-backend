package com.application.e_wallet.treasury.dto;

import java.math.BigDecimal;

public record WalletMovement(
        String accountName,
        String action,
        BigDecimal previousBalance,
        BigDecimal newBalance
) {
}
