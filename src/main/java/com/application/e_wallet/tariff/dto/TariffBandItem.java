package com.application.e_wallet.tariff.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** One fee and commission band for a transaction type and amount range. */
public record TariffBandItem(
        UUID id,
        String transactionType,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        BigDecimal fee,
        BigDecimal agentCommission,
        boolean active
) {
}
