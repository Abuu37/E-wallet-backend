package com.application.e_wallet.dashboard;

import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.transaction.entity.TransactionEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

// Shared by both the admin and agent dashboards: resolving a TODAY/WEEK/CUSTOM range into a
// concrete query window, and bucketing a transaction list into an hourly (TODAY) or daily
// (WEEK/CUSTOM) trend. Centralized here rather than duplicated per dashboard, since both need
// byte-for-byte identical zone/boundary handling.
public final class DashboardRangeSupport {

    public static final ZoneId DASHBOARD_ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private DashboardRangeSupport() {
    }

    public record DayWindow(OffsetDateTime start, OffsetDateTime end) {
    }

    // periodStart/periodEnd are the inclusive calendar-day bounds the caller asked for (or that
    // TODAY/WEEK resolve to) - kept alongside the instant-based DayWindow because window.end for
    // a wholly-past CUSTOM range is an exact midnight boundary, from which the *calendar date*
    // containing it can't be reliably recovered (it would read as the day after periodEnd).
    public record TransactionPeriod(DayWindow window, LocalDate periodStart, LocalDate periodEnd) {
    }

    public static DayWindow today() {
        OffsetDateTime now = OffsetDateTime.now(DASHBOARD_ZONE);
        OffsetDateTime startOfDay = now.toLocalDate().atStartOfDay(DASHBOARD_ZONE).toOffsetDateTime();
        return new DayWindow(startOfDay, now);
    }

    public static DashboardRange parseRange(String rawRange) {
        try {
            return DashboardRange.valueOf(rawRange.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid range '" + rawRange + "'. Must be one of: today, week, custom");
        }
    }

    public static TransactionPeriod resolvePeriod(DashboardRange range, LocalDate from, LocalDate to) {
        OffsetDateTime now = OffsetDateTime.now(DASHBOARD_ZONE);
        LocalDate today = now.toLocalDate();

        LocalDate periodStart;
        LocalDate periodEnd;
        switch (range) {
            case TODAY -> {
                periodStart = today;
                periodEnd = today;
            }
            case WEEK -> {
                periodStart = today.minusDays(6);
                periodEnd = today;
            }
            case CUSTOM -> {
                if (from == null || to == null) {
                    throw new BadRequestException("'from' and 'to' are required when range=custom");
                }
                if (from.isAfter(to)) {
                    throw new BadRequestException("'from' must not be after 'to'");
                }
                periodStart = from;
                periodEnd = to;
            }
            default -> throw new IllegalStateException("Unhandled range: " + range);
        }

        OffsetDateTime start = periodStart.atStartOfDay(DASHBOARD_ZONE).toOffsetDateTime();
        OffsetDateTime endOfPeriod = periodEnd.plusDays(1).atStartOfDay(DASHBOARD_ZONE).toOffsetDateTime();
        OffsetDateTime end = endOfPeriod.isBefore(now) ? endOfPeriod : now;

        return new TransactionPeriod(new DayWindow(start, end), periodStart, periodEnd);
    }

    public static List<TrendPoint> buildHourlyTrend(List<TransactionEntity> transactions, DayWindow window) {
        int lastHour = window.end().atZoneSameInstant(DASHBOARD_ZONE).getHour();

        long[] counts = new long[lastHour + 1];
        BigDecimal[] volumes = new BigDecimal[lastHour + 1];
        for (int i = 0; i <= lastHour; i++) {
            volumes[i] = BigDecimal.ZERO;
        }

        for (TransactionEntity transaction : transactions) {
            int hour = transaction.getCreatedAt().atZoneSameInstant(DASHBOARD_ZONE).getHour();
            if (hour >= 0 && hour <= lastHour) {
                counts[hour]++;
                volumes[hour] = volumes[hour].add(transaction.getAmount());
            }
        }

        List<TrendPoint> hourlyTrend = new ArrayList<>();
        for (int hour = 0; hour <= lastHour; hour++) {
            hourlyTrend.add(new TrendPoint(String.format("%02d:00", hour), normalizeScale(volumes[hour]), counts[hour]));
        }
        return hourlyTrend;
    }

    // One bucket per calendar day in [periodStart, periodEnd], inclusive on both ends - used for
    // WEEK/CUSTOM ranges, where hourly buckets would be unreadable (168+ bars for a week).
    public static List<TrendPoint> buildDailyTrend(List<TransactionEntity> transactions, LocalDate periodStart, LocalDate periodEnd) {
        int days = (int) ChronoUnit.DAYS.between(periodStart, periodEnd) + 1;

        long[] counts = new long[days];
        BigDecimal[] volumes = new BigDecimal[days];
        for (int i = 0; i < days; i++) {
            volumes[i] = BigDecimal.ZERO;
        }

        for (TransactionEntity transaction : transactions) {
            LocalDate day = transaction.getCreatedAt().atZoneSameInstant(DASHBOARD_ZONE).toLocalDate();
            int index = (int) ChronoUnit.DAYS.between(periodStart, day);
            if (index >= 0 && index < days) {
                counts[index]++;
                volumes[index] = volumes[index].add(transaction.getAmount());
            }
        }

        List<TrendPoint> dailyTrend = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate day = periodStart.plusDays(i);
            dailyTrend.add(new TrendPoint(day.toString(), normalizeScale(volumes[i]), counts[i]));
        }
        return dailyTrend;
    }

    public static List<TrendPoint> buildTrend(DashboardRange range, List<TransactionEntity> transactions, TransactionPeriod period) {
        return range == DashboardRange.TODAY
                ? buildHourlyTrend(transactions, period.window())
                : buildDailyTrend(transactions, period.periodStart(), period.periodEnd());
    }

    public static BigDecimal normalizeScale(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
