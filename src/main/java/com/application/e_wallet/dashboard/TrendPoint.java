package com.application.e_wallet.dashboard;

import java.math.BigDecimal;

// "period" is an hour label ("00:00".."23:00") for a TODAY range, or an ISO date
// ("2026-11-16") for a WEEK/CUSTOM range - see DashboardRangeSupport.
public record TrendPoint(String period, BigDecimal volume, long count) {
}
