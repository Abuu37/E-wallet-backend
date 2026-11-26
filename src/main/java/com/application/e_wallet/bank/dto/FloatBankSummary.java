package com.application.e_wallet.bank.dto;

import java.math.BigDecimal;

/** An agent's bank top-ups today against the daily cap, for the Float screen meter. */
public record FloatBankSummary(BigDecimal dailyCap, BigDecimal usedToday, BigDecimal leftToday) {
}
