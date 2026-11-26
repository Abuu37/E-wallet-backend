package com.application.e_wallet.treasury.operations;

public enum OperationType {
    /** Revenue the company takes out: revenue and escrow both go down, and the money is paid to the company bank. */
    SWEEP,
    /** A wrong wallet-to-wallet transfer is undone with a new opposite entry. */
    REVERSAL,
    /** The company paid its own money into escrow: escrow and revenue both go up. */
    ADJUSTMENT_PAY_IN,
    /** The bank took a charge from escrow: escrow and revenue both go down. */
    ADJUSTMENT_CHARGE
}
