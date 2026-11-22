package com.application.e_wallet.dashboard.customer.dto;

import java.math.BigDecimal;

// In-progress incoming activity, e.g. a Stripe top-up the customer paid for whose webhook
// hasn't landed yet - not scoped to any particular channel, just "money on its way in".
public record CustomerPendingActivity(
        long count,
        BigDecimal amount
) {
}
