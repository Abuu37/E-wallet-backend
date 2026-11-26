package com.application.e_wallet.tariff.dto;

import java.math.BigDecimal;

/** What an action costs before the person confirms it. */
public record QuoteResponse(String type, BigDecimal amount, BigDecimal fee, BigDecimal total) {
}
