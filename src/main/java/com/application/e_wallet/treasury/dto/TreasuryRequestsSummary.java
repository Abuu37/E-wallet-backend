package com.application.e_wallet.treasury.dto;

public record TreasuryRequestsSummary(
        long totalRequests,
        RequestStatusSummary pending,
        RequestStatusSummary approved,
        RequestStatusSummary rejected
) {
}
