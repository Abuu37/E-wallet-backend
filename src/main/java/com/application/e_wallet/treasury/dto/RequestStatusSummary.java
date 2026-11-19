package com.application.e_wallet.treasury.dto;

import java.math.BigDecimal;

public record RequestStatusSummary(long count, BigDecimal balance) {
}
