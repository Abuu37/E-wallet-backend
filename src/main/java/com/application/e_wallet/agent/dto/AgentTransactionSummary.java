package com.application.e_wallet.agent.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

// Sourced from the ledger (transaction_journal/transaction_journal_line), not the
// not-yet-built customer-facing `transactions` table. Every ledger entry is already
// posted and final by the time it exists, so status is always COMPLETED — the ledger
// has no pending/failed concept. balanceAfter isn't tracked per entry; externalReference
// is a treasury-request-specific field, not something journal entries carry.
public record AgentTransactionSummary(
        String internalReference,
        String accountNumber,
        String type,
        String direction,
        BigDecimal amount,
        String status,
        OffsetDateTime createdAt
) {
}
