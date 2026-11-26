package com.application.e_wallet.agent.activity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One transaction an agent conducted: a cash-in they made, or a customer withdrawal paid at their till.
 * In the list the phone is masked; in the single-transaction detail it is shown in full.
 */
public record AgentTransactionRow(
        String reference,
        /** CASH_IN or CASH_OUT. */
        String type,
        String customerName,
        String customerPhone,
        String customerAccount,
        BigDecimal amount,
        BigDecimal customerFee,
        BigDecimal commission,
        String status,
        OffsetDateTime createdAt
) {
}
