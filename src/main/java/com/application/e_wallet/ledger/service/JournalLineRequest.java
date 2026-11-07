package com.application.e_wallet.ledger.service;

import com.application.e_wallet.ledger.entity.EntryType;
import com.application.e_wallet.ledger.entity.LedgerAccountType;

import java.math.BigDecimal;
import java.util.UUID;

public record JournalLineRequest(
        LedgerAccountType ledgerAccountType,
        UUID accountRefId,
        EntryType entryType,
        BigDecimal amount
) {
}
