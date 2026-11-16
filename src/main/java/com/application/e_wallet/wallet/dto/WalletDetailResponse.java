package com.application.e_wallet.wallet.dto;

import com.application.e_wallet.wallet.entity.WalletStatus;
import com.application.e_wallet.wallet.entity.WalletType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record WalletDetailResponse(
        UUID walletId,
        String accountNumber,
        WalletType accountType,
        WalletOwner owner,
        BigDecimal balance,
        String currency,
        WalletStatus status,
        OffsetDateTime createdAt,
        List<WalletLedgerEntry> recentLedgerEntries
) {
}
