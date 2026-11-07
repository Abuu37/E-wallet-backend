package com.application.e_wallet.company_wallet.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record CompanyMasterWalletResponse(
        UUID walletId,
        String walletName,
        String currency,
        BigDecimal unallocatedFloat,
        BigDecimal totalDistributedFloat,
        String escrowBankAccountNo
) {
}
