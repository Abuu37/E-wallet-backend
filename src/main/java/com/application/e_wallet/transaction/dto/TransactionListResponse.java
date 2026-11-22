package com.application.e_wallet.transaction.dto;

import java.util.List;

public record TransactionListResponse(
        List<TransactionListItem> transactions,
        int page,
        int size,
        long totalElements
) {
}
