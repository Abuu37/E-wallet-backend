package com.application.e_wallet.dashboard.dto;

import com.application.e_wallet.treasury.entity.RequestType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PendingFloatRequestItem(
        UUID requestId,
        String agentCode,
        String businessName,
        RequestType requestType,
        String floatAccountNumber,
        BigDecimal amount,
        String externalReference,
        OffsetDateTime requestedAt
) {
}
