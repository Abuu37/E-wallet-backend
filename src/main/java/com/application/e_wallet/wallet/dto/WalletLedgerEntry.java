package com.application.e_wallet.wallet.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

// Sourced from the ledger (transaction_journal/transaction_journal_line). externalReference
// is a treasury-request-specific field, not something journal entries carry, and
// balanceBefore/balanceAfter aren't tracked per entry - only the wallet's current balance is.
public record WalletLedgerEntry(
        UUID entryId,
        String internalReference,
        String transactionType,
        String entryType,
        BigDecimal amount,
        String description,
        OffsetDateTime createdAt
) {
}
