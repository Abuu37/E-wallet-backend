package com.application.e_wallet.kyc.dto;

import java.math.BigDecimal;

/**
 * The customer's verification state and the limits of their current tier. Both tiers are included so the
 * screen can show what verifying unlocks without hard-coding any number.
 */
public record KycStatusResponse(
        String status,
        String tier,
        BigDecimal perPaymentLimit,
        BigDecimal dailyLimit,
        BigDecimal maxBalance,
        Limits basic,
        Limits verified,
        boolean sandbox
) {

    public record Limits(BigDecimal perPayment, BigDecimal daily, BigDecimal maxBalance) {
    }
}
