package com.application.e_wallet.wallet.dto;

import com.application.e_wallet.wallet.entity.WalletStatus;
import com.application.e_wallet.wallet.entity.WalletType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record WalletListItem(
        UUID walletId,
        String accountNumber,
        WalletType accountType,
        String ownerName,
        String ownerCode,
        BigDecimal balance,
        String currency,
        WalletStatus status,
        OffsetDateTime updatedAt
) {
}
