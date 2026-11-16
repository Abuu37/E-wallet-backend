package com.application.e_wallet.dashboard.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PendingFloatRequestItem(
        UUID requestId,
        String agentCode,
        String businessName,
        String floatAccountNumber,
        BigDecimal amount,
        String externalReference,
        OffsetDateTime requestedAt
) {
}
