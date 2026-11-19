package com.application.e_wallet.treasury.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record JournalLineResponse(
        UUID walletId,
        String accountNumber,
        String accountOwner,
        String walletType,
        String entryType,
        BigDecimal amount
) {
}
