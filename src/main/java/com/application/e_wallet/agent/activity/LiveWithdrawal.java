package com.application.e_wallet.agent.activity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A customer withdrawal just paid at the agent's till, for the live feed. The code is what the customer shows the agent. */
public record LiveWithdrawal(String reference, BigDecimal amount, String customerName, String customerPhone, String matchCode, BigDecimal commission, OffsetDateTime createdAt) {
}
