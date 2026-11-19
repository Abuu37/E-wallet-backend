package com.application.e_wallet.dashboard.admin.dto;

import java.math.BigDecimal;

public record HourlyTrendPoint(String hour, BigDecimal volume, long count) {
}
