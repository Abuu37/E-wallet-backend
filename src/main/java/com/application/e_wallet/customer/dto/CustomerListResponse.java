package com.application.e_wallet.customer.dto;

import java.util.List;

public record CustomerListResponse(
        CustomerListSummary summary,
        List<CustomerSummaryResponse> customers,
        int page,
        int size,
        long totalElements
) {
}
