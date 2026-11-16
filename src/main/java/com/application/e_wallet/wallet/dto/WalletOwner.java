package com.application.e_wallet.wallet.dto;

import java.util.UUID;

public record WalletOwner(
        UUID userId,
        String name,
        String phoneNumber,
        String role
) {
}
