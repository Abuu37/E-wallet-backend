package com.application.e_wallet.wallet.dto;

/** Who owns this number, enough to be sure it is the right person ("Amina W.") and no more. */
public record LookupResponse(boolean found, String name, boolean canReceive) {

    public static LookupResponse notFound() {
        return new LookupResponse(false, null, false);
    }
}
