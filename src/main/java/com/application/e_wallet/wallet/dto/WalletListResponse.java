package com.application.e_wallet.wallet.dto;

import java.util.List;

public record WalletListResponse(
        WalletSummaryStats summary,
        List<WalletListItem> wallets,
        int page,
        int size,
        long totalElements
) {
}
