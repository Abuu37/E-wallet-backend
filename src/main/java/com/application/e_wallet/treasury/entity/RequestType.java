package com.application.e_wallet.treasury.entity;

public enum RequestType {
    TOPUP,
    WITHDRAWAL,
    /** Staff request to mint float from the escrow bank; needs a second staff member. */
    MINT,
    /** Staff request to fund the commission pool; needs a second staff member. */
    COMMISSION_TOPUP
}
