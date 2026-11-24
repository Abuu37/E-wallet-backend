package com.application.e_wallet.audit.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One audit entry. before/after are the raw JSON the action recorded; never PINs, OTPs or tokens. */
public record AuditLogItem(
        UUID id,
        OffsetDateTime createdAt,
        UUID actorId,
        String actorName,
        String module,
        String action,
        UUID entityId,
        String beforeJson,
        String afterJson,
        String reason,
        String ipAddress
) {
}
