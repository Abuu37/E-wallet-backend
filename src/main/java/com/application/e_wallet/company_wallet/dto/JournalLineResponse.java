package com.application.e_wallet.company_wallet.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record JournalLineResponse(
        String ledgerAccountType,
        UUID accountRefId,
        String entryType,
        BigDecimal amount
) {
}
