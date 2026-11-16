package com.application.e_wallet.dashboard.dto;

import java.math.BigDecimal;

public record HourlyTrendPoint(String hour, BigDecimal volume, long count) {
}
