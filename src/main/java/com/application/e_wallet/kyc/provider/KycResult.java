package com.application.e_wallet.kyc.provider;

import java.util.UUID;

/**
 * @param providerRef the provider's id for this verification (null when none exists yet)
 * @param customerId  our customer id, which is the only identifier we ever send to the provider
 */
public record KycResult(Outcome outcome, String providerRef, UUID customerId) {

    public enum Outcome { PASSED, FAILED, PENDING }
}
