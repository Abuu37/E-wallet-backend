package com.application.e_wallet.wallet.dto;

import java.time.OffsetDateTime;

/** A saved recipient. The phone is the owner's own contact, so it is shown in full. */
public record BeneficiaryItem(String name, String phone, OffsetDateTime lastUsedAt) {
}
