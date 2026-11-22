package com.application.e_wallet.dashboard.customer.dto;

import com.application.e_wallet.customer.dto.CustomerTransactionItem;
import com.application.e_wallet.customer.dto.CustomerWalletSummary;

import java.time.OffsetDateTime;
import java.util.List;

public record CustomerDashboardOverviewResponse(
        OffsetDateTime timestamp,
        CustomerDashboardProfile profile,
        CustomerWalletSummary wallet,
        CustomerMonthlyCashFlow monthlyCashFlow,
        CustomerPendingActivity pendingActivity,
        List<CustomerTransactionItem> recentTransactions
) {
}
