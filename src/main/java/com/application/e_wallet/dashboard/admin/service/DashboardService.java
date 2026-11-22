package com.application.e_wallet.dashboard.admin.service;

import com.application.e_wallet.agent.dto.FloatHealth;
import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.entity.AgentStatus;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.agent.repository.LowFloatAgentProjection;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.customer.entity.CustomerStatus;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.dashboard.admin.dto.ActionAlerts;
import com.application.e_wallet.dashboard.admin.dto.DashboardOverviewResponse;
import com.application.e_wallet.dashboard.admin.dto.DashboardRange;
import com.application.e_wallet.dashboard.admin.dto.LiquidityCards;
import com.application.e_wallet.dashboard.admin.dto.LowFloatAgentItem;
import com.application.e_wallet.dashboard.admin.dto.LowFloatAgentsSummary;
import com.application.e_wallet.dashboard.admin.dto.NetworkStats;
import com.application.e_wallet.dashboard.admin.dto.PendingFloatRequestItem;
import com.application.e_wallet.dashboard.admin.dto.PendingFloatRequestsSummary;
import com.application.e_wallet.dashboard.admin.dto.RecentTransactionItem;
import com.application.e_wallet.dashboard.admin.dto.TransactionBreakdown;
import com.application.e_wallet.dashboard.admin.dto.TransactionMetrics;
import com.application.e_wallet.dashboard.admin.dto.TransactionTypeMetric;
import com.application.e_wallet.dashboard.admin.dto.TrendPoint;
import com.application.e_wallet.ledger.entity.EntryType;
import com.application.e_wallet.ledger.repository.TransactionJournalLineRepository;
import com.application.e_wallet.transaction.entity.TransactionEntity;
import com.application.e_wallet.transaction.entity.TransactionStatus;
import com.application.e_wallet.transaction.entity.TransactionType;
import com.application.e_wallet.transaction.repository.TransactionRepository;
import com.application.e_wallet.treasury.entity.RequestStatus;
import com.application.e_wallet.treasury.entity.TreasuryRequestEntity;
import com.application.e_wallet.treasury.repository.TreasuryRequestRepository;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DashboardService {


    static final ZoneId DASHBOARD_ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private final WalletRepository walletRepository;
    private final TransactionJournalLineRepository transactionJournalLineRepository;
    private final TransactionRepository transactionRepository;
    private final TreasuryRequestRepository treasuryRequestRepository;
    private final AgentRepository agentRepository;
    private final CustomerRepository customerRepository;

    //============ Package-private for the every "today"-scoped section ============== //
    record DayWindow(OffsetDateTime start, OffsetDateTime end) {
    }

    //=============== Package-private so tests can compute the same today-window the section builders use.
    static DayWindow today() {
        OffsetDateTime now = OffsetDateTime.now(DASHBOARD_ZONE);
        OffsetDateTime startOfDay = now.toLocalDate().atStartOfDay(DASHBOARD_ZONE).toOffsetDateTime();
        return new DayWindow(startOfDay, now);
    }

    // periodStart/periodEnd are the inclusive calendar-day bounds the caller asked for (or that
    // TODAY/WEEK resolve to) - kept alongside the instant-based DayWindow because window.end for
    // a wholly-past CUSTOM range is an exact midnight boundary, from which the *calendar date*
    // containing it can't be reliably recovered (it would read as the day after periodEnd).
    record TransactionPeriod(DayWindow window, LocalDate periodStart, LocalDate periodEnd) {
    }

    // Package-private so tests can resolve the same period the section builder uses.
    static TransactionPeriod resolveTransactionPeriod(DashboardRange range, LocalDate from, LocalDate to) {
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

    private static DashboardRange parseRange(String rawRange) {
        try {
            return DashboardRange.valueOf(rawRange.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid range '" + rawRange + "'. Must be one of: today, week, custom");
        }
    }

    LiquidityCards buildLiquidityCards() {
        BigDecimal totalDebits = transactionJournalLineRepository.sumAmountByEntryType(EntryType.DEBIT);
        BigDecimal totalCredits = transactionJournalLineRepository.sumAmountByEntryType(EntryType.CREDIT);
        boolean isLedgerBalanced = totalDebits.compareTo(totalCredits) == 0;

        return new LiquidityCards(
                normalizeScale(walletRepository.sumBalanceByWalletType(WalletType.ESCROW_BANK)),
                normalizeScale(walletRepository.sumBalanceByWalletType(WalletType.COMPANY_MASTER_ACCOUNT)),
                normalizeScale(walletRepository.sumBalanceByWalletType(WalletType.REVENUE)),
                normalizeScale(walletRepository.sumBalanceByWalletType(WalletType.AGENT_FLOAT)),
                normalizeScale(walletRepository.sumBalanceByWalletType(WalletType.AGENT_COMMISSION)),
                normalizeScale(walletRepository.sumBalanceByWalletType(WalletType.CUSTOMER_MAIN)),
                isLedgerBalanced
        );
    }

    TransactionMetrics buildTransactionMetrics(DashboardRange range, TransactionPeriod period) {
        DayWindow window = period.window();
        List<TransactionEntity> transactions = transactionRepository.findByStatusAndCreatedAtBetween(
                TransactionStatus.COMPLETED.name(), window.start(), window.end());

        BigDecimal totalVolume = BigDecimal.ZERO;
        long totalCount = 0;
        for (TransactionEntity transaction : transactions) {
            totalVolume = totalVolume.add(transaction.getAmount());
            totalCount++;
        }

        TransactionBreakdown breakdown = new TransactionBreakdown(
                summarize(transactions, TransactionType.CASH_IN),
                summarize(transactions, TransactionType.CASH_OUT),
                summarize(transactions, TransactionType.SERVICE_FEE),
                summarize(transactions, TransactionType.SERVICE_COMMISSION)
        );

        List<TrendPoint> trend = range == DashboardRange.TODAY
                ? buildHourlyTrend(transactions, window)
                : buildDailyTrend(transactions, period.periodStart(), period.periodEnd());

        return new TransactionMetrics(range.name(), period.periodStart(), period.periodEnd(), totalVolume, totalCount, breakdown, trend);
    }

    private static TransactionTypeMetric summarize(List<TransactionEntity> transactions, TransactionType type) {
        List<TransactionEntity> filtered = transactions.stream()
                .filter(transaction -> transaction.getTransactionType().equals(type.name()))
                .toList();
        return summarize(filtered);
    }

    // Private static: shared count+volume reduction used both for type-filtered breakdown
    // buckets and for status-filtered transaction lists (pending/failed today) that don't
    // need to re-filter by type.
    private static TransactionTypeMetric summarize(List<TransactionEntity> transactions) {
        long count = 0;
        BigDecimal volume = BigDecimal.ZERO;
        for (TransactionEntity transaction : transactions) {
            count++;
            volume = volume.add(transaction.getAmount());
        }
        return new TransactionTypeMetric(count, normalizeScale(volume));
    }

    private static List<TrendPoint> buildHourlyTrend(List<TransactionEntity> transactions, DayWindow window) {
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
    private static List<TrendPoint> buildDailyTrend(List<TransactionEntity> transactions, LocalDate periodStart, LocalDate periodEnd) {
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


    ActionAlerts buildActionAlerts() {
        return new ActionAlerts(buildPendingFloatRequestsSummary(), buildLowFloatAgentsSummary());
    }

    private PendingFloatRequestsSummary buildPendingFloatRequestsSummary() {
        long count = treasuryRequestRepository.countByStatus(RequestStatus.PENDING);
        BigDecimal totalRequestedAmount = normalizeScale(treasuryRequestRepository.sumAmountByStatus(RequestStatus.PENDING));
        List<TreasuryRequestEntity> recent =
                treasuryRequestRepository.findTop10ByStatusOrderByCreatedAtDesc(RequestStatus.PENDING);

        List<PendingFloatRequestItem> recentRequests = new ArrayList<>();
        for (TreasuryRequestEntity request : recent) {
            recentRequests.add(toPendingFloatRequestItem(request));
        }

        return new PendingFloatRequestsSummary(count, totalRequestedAmount, recentRequests);
    }

    private PendingFloatRequestItem toPendingFloatRequestItem(TreasuryRequestEntity request) {
        AgentEntity agent = request.getAgent();
        WalletEntity wallet = walletRepository.findByUserIdAndWalletType(agent.getUser().getId(), WalletType.AGENT_FLOAT)
                .orElseThrow(() -> new IllegalStateException("Agent float wallet not found"));

        return new PendingFloatRequestItem(
                request.getId(),
                agent.getAgentNumber(),
                agent.getBusinessName(),
                request.getRequestType(),
                wallet.getAccountNumber(),
                request.getAmount(),
                request.getExternalReference(),
                request.getCreatedAt()
        );
    }

    private LowFloatAgentsSummary buildLowFloatAgentsSummary() {
        List<LowFloatAgentProjection> projections =
                agentRepository.findLowFloatAgents(WalletType.AGENT_FLOAT, FloatHealth.LOW_THRESHOLD, AgentStatus.ACTIVE);

        List<LowFloatAgentItem> agents = new ArrayList<>();
        for (LowFloatAgentProjection projection : projections) {
            agents.add(new LowFloatAgentItem(
                    projection.getAgentId(),
                    projection.getAgentCode(),
                    projection.getBusinessName(),
                    projection.getPhoneNumber(),
                    projection.getFloatAccountNumber(),
                    projection.getCurrentBalance()
            ));
        }

        return new LowFloatAgentsSummary(normalizeScale(FloatHealth.LOW_THRESHOLD), agents.size(), agents);
    }

    // Package-private so tests can call this section builder directly without going through
    // the public getOverview() entry point.
    NetworkStats buildNetworkStats(DayWindow window) {
        long totalActiveAgents = agentRepository.countByAgentStatus(AgentStatus.ACTIVE);
        long totalActiveCustomers = customerRepository.countByStatus(CustomerStatus.ACTIVE);

        List<TransactionEntity> pendingTransactions = transactionRepository.findByStatusAndCreatedAtBetween(
                TransactionStatus.PENDING.name(), window.start(), window.end());
        List<TransactionEntity> failedTransactions = transactionRepository.findByStatusAndCreatedAtBetween(
                TransactionStatus.FAILED.name(), window.start(), window.end());

        return new NetworkStats(
                totalActiveAgents,
                totalActiveCustomers,
                summarize(pendingTransactions),
                summarize(failedTransactions)
        );
    }

    // Package-private so tests can call this section builder directly without going through
    // the public getOverview() entry point.
    List<RecentTransactionItem> buildRecentTransactions() {
        List<TransactionEntity> transactions = transactionRepository.findRecentWithWallets(PageRequest.of(0, 10));

        List<RecentTransactionItem> items = new ArrayList<>();
        for (TransactionEntity transaction : transactions) {
            items.add(new RecentTransactionItem(
                    transaction.getReferenceNo(),
                    transaction.getTransactionType(),
                    transaction.getAmount(),
                    transaction.getStatus(),
                    transaction.getCreatedAt(),
                    transaction.getSenderWallet().getAccountName(),
                    transaction.getSenderWallet().getAccountNumber(),
                    transaction.getReceiverWallet().getAccountName(),
                    transaction.getReceiverWallet().getAccountNumber()
            ));
        }
        return items;
    }

    @Transactional(readOnly = true)
    public DashboardOverviewResponse getOverview(String rawRange, LocalDate from, LocalDate to) {
        DashboardRange range = parseRange(rawRange);
        TransactionPeriod period = resolveTransactionPeriod(range, from, to);
        // networkStats stays fixed to "today" regardless of the requested range - it's an
        // operational/real-time alert ("how much is pending/failing right now"), not historical
        // volume reporting, so it shouldn't change meaning when range=week or range=custom.
        DayWindow todayWindow = today();

        return new DashboardOverviewResponse(
                OffsetDateTime.now(DASHBOARD_ZONE), "TZS",
                buildLiquidityCards(), buildTransactionMetrics(range, period), buildActionAlerts(), buildNetworkStats(todayWindow),
                buildRecentTransactions());
    }

    private static BigDecimal normalizeScale(BigDecimal amount) {

        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
