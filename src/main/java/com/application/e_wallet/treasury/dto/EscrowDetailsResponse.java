package com.application.e_wallet.treasury.dto;

/** Where an agent deposits when they request float, and the reference to write on the deposit. */
public record EscrowDetailsResponse(String bankName, String accountName, String accountNumber, String reference) {
}
