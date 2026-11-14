package com.application.e_wallet.ledger.dto;

import com.application.e_wallet.ledger.entity.EntryType;

import java.math.BigDecimal;
import java.util.UUID;

public record JournalLineRequest(
        UUID walletId,
        EntryType entryType,
        BigDecimal amount
) {
}
