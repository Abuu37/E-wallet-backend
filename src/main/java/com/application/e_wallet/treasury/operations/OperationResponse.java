package com.application.e_wallet.treasury.operations;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record OperationResponse(
        UUID id,
        String opRef,
        String type,
        BigDecimal amount,
        String reason,
        String targetReference,
        String status,
        String requestedBy,
        String decidedBy,
        String decisionNote,
        String resultReference,
        OffsetDateTime createdAt,
        OffsetDateTime decidedAt,
        /** True when the signed-in staff member asked for this one, so only someone else can approve it. */
        boolean askedByYou
) {
}
