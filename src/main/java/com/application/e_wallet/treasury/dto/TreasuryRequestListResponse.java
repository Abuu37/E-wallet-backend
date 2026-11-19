package com.application.e_wallet.treasury.dto;

import java.util.List;

public record TreasuryRequestListResponse(
        TreasuryRequestsSummary summary,
        List<TreasuryRequestResponse> requests
) {
}
