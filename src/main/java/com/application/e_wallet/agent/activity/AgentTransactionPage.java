package com.application.e_wallet.agent.activity;

import java.math.BigDecimal;
import java.util.List;

/** A page of the agent's transactions with the numbers for the KPI strip above the table. */
public record AgentTransactionPage(
        List<AgentTransactionRow> items,
        int page,
        int size,
        long totalElements,
        Kpis kpis
) {
    /** Today's numbers, whatever the filters are. */
    public record Kpis(long todayCount, long cashInToday, long withdrawalsToday, long pendingToday, BigDecimal commissionToday) { }
}
