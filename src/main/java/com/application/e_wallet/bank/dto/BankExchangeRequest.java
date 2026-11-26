package com.application.e_wallet.bank.dto;

import jakarta.validation.constraints.NotBlank;

/** What Plaid Link hands the browser when the person finishes: a one-time public token and the account they chose. */
public record BankExchangeRequest(
        @NotBlank(message = "The bank link did not finish") String publicToken,
        /** The account chosen in Link. When Link gives none, the first checking or savings account is used. */
        String accountId
) {
}
