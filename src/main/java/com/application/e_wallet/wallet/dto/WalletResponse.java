package com.application.e_wallet.wallet.dto;

import com.application.e_wallet.wallet.entity.WalletStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record WalletResponse(
        UUID walletId,
        String ownerType,
        String walletNumber,
        String currency,
        WalletStatus status,
        String accountNumber,
        BigDecimal balance
) {
}
