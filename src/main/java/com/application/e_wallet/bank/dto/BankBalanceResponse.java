package com.application.e_wallet.bank.dto;

import java.math.BigDecimal;

/** What is in the bank, in TZS. In Sandbox the bank holds USD, so the rate used is shown too. */
public record BankBalanceResponse(BigDecimal availableTzs, BigDecimal availableUsd, BigDecimal usdToTzs, boolean demo) {
}
