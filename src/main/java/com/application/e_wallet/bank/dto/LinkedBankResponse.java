package com.application.e_wallet.bank.dto;

import java.util.UUID;

/** A linked bank as the app shows it. Never carries a token or a full account number. */
public record LinkedBankResponse(
        UUID id,
        String institutionName,
        String accountName,
        String accountMask,
        /** MATCHED, SKIPPED_SANDBOX (demo only) or MISMATCH. Only the first two can be used for money. */
        String nameCheck,
        boolean usable
) {
}
