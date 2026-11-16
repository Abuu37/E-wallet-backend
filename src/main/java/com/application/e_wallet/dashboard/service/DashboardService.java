package com.application.e_wallet.dashboard.service;

import com.application.e_wallet.agent.dto.FloatHealth;
import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.entity.AgentStatus;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.agent.repository.LowFloatAgentProjection;
import com.application.e_wallet.customer.entity.CustomerStatus;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.dashboard.dto.ActionAlerts;
import com.application.e_wallet.dashboard.dto.DashboardOverviewResponse;
import com.application.e_wallet.dashboard.dto.HourlyTrendPoint;
import com.application.e_wallet.dashboard.dto.LiquidityCards;
import com.application.e_wallet.dashboard.dto.LowFloatAgentItem;
import com.application.e_wallet.dashboard.dto.LowFloatAgentsSummary;
import com.application.e_wallet.dashboard.dto.NetworkStats;
import com.application.e_wallet.dashboard.dto.PendingFloatRequestItem;
import com.application.e_wallet.dashboard.dto.PendingFloatRequestsSummary;
import com.application.e_wallet.dashboard.dto.TodayTransactionMetrics;
import com.application.e_wallet.dashboard.dto.TransactionBreakdown;
import com.application.e_wallet.dashboard.dto.TransactionTypeMetric;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DashboardService {

    // Package-private so tests can reference the same zone the DayWindow/today() helpers below
    // use when building "today" windows for the dashboard's day-scoped sections.
    static final ZoneId DASHBOARD_ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private final WalletRepository walletRepository;
    private final TransactionJournalLineRepository transactionJournalLineRepository;
    private final TransactionRepository transactionRepository;
    private final TreasuryRequestRepository treasuryRequestRepository;
    private final AgentRepository agentRepository;
    private final CustomerRepository customerRepository;

    // Package-private so every "today"-scoped section builder shares one window shape.
    record DayWindow(OffsetDateTime start, OffsetDateTime end) {
    }

    // Package-private so tests can compute the same today-window the section builders use.
    static DayWindow today() {
        OffsetDateTime now = OffsetDateTime.now(DASHBOARD_ZONE);
        OffsetDateTime startOfDay = now.toLocalDate().atStartOfDay(DASHBOARD_ZONE).toOffsetDateTime();
        return new DayWindow(startOfDay, now);
    }

    // Package-private so tests can call this section builder directly without going through
    // the public getOverview() entry point.
    LiquidityCards buildLiquidityCards() {
        BigDecimal totalDebits = transactionJournalLineRepository.sumAmountByEntryType(EntryType.DEBIT);
        BigDecimal totalCredits = transactionJournalLineRepository.sumAmountByEntryType(EntryType.CREDIT);
        // COALESCE(SUM(...), 0) can come back at a different scale than the NUMERIC(19,2)
        // column (e.g. scale-0 literal 0 vs scale-2 0.00); BigDecimal.equals() treats scale
        // as significant, so comparing via compareTo(...) == 0 is required here.
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

    // Package-private so tests can call this section builder directly without going through
    // the public getOverview() entry point.
    TodayTransactionMetrics buildTodayTransactionMetrics(DayWindow window) {
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
                summarize(transactions, TransactionType.INTERNAL_TRANSFER),
                summarize(transactions, TransactionType.COMMISSION_TRANSFER)
        );

        List<HourlyTrendPoint> hourlyTrend = buildHourlyTrend(transactions, window);

        return new TodayTransactionMetrics(totalVolume, totalCount, breakdown, hourlyTrend);
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

    private static List<HourlyTrendPoint> buildHourlyTrend(List<TransactionEntity> transactions, DayWindow window) {
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

        List<HourlyTrendPoint> hourlyTrend = new ArrayList<>();
        for (int hour = 0; hour <= lastHour; hour++) {
            hourlyTrend.add(new HourlyTrendPoint(String.format("%02d:00", hour), normalizeScale(volumes[hour]), counts[hour]));
        }
        return hourlyTrend;
    }

    // Package-private so tests can call this section builder directly without going through
    // the public getOverview() entry point.
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

    @Transactional(readOnly = true)
    public DashboardOverviewResponse getOverview() {
        DayWindow window = today();
        return new DashboardOverviewResponse(
                OffsetDateTime.now(DASHBOARD_ZONE), "TZS",
                buildLiquidityCards(), buildTodayTransactionMetrics(window), buildActionAlerts(), buildNetworkStats(window));
    }

    // SUM(...) over no matching rows comes back via the COALESCE fallback, whose literal
    // scale isn't guaranteed to match the NUMERIC(19,2) column scale across JPA providers.
    private static BigDecimal normalizeScale(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
