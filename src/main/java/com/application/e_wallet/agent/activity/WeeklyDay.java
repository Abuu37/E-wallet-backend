package com.application.e_wallet.agent.activity;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One day of the 7-day chart: cash-ins against customer withdrawals. */
public record WeeklyDay(LocalDate date, long cashInCount, BigDecimal cashInAmount, long withdrawalCount, BigDecimal withdrawalAmount) {
}
