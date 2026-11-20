package com.application.e_wallet.customer.dto;

import java.math.BigDecimal;

public record CustomerListSummary(
        long totalCustomers,
        long activeCustomers,
        long suspendedCustomers,
        BigDecimal totalCustomerWalletBalance
) {
}
