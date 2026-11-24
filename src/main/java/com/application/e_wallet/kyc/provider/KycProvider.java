package com.application.e_wallet.kyc.provider;

import java.util.UUID;

/**
 * Identity verification behind our own interface, so a Tanzanian KYC partner can replace Plaid later
 * without touching screens, limits or the ledger.
 */
public interface KycProvider {

    /** Starts a verification for this customer and returns the short-lived token the client opens. */
    KycSession createSession(UUID customerId);

    /** The most recent verification this customer has with the provider (PENDING if none exists yet). */
    KycResult fetchLatestResult(UUID customerId);

    /** A specific verification, e.g. the one a webhook refers to. */
    KycResult fetchResult(String providerRef);
}
