package com.application.e_wallet.company_wallet.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record TransactionJournalResponse(
        UUID journalId,
        String referenceNumber,
        String transactionType,
        String description,
        UUID createdByUserId,
        OffsetDateTime createdAt,
        List<JournalLineResponse> lines
) {
}
