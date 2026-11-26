package com.application.e_wallet.treasury.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record FloatRequestResponse(UUID requestId, String requestRef, String status, BigDecimal amount, String depositReference, OffsetDateTime createdAt) {
}
