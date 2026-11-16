package com.application.e_wallet.dashboard.service;

import com.application.e_wallet.agent.dto.FloatHealth;
import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.entity.AgentStatus;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.agent.repository.LowFloatAgentProjection;
import com.application.e_wallet.dashboard.dto.ActionAlerts;
import com.application.e_wallet.dashboard.dto.HourlyTrendPoint;
import com.application.e_wallet.dashboard.dto.LiquidityCards;
import com.application.e_wallet.dashboard.dto.LowFloatAgentItem;
import com.application.e_wallet.dashboard.dto.LowFloatAgentsSummary;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DashboardService {

    // Package-private: Tasks 5 and 6 reuse this zone and the DayWindow/today() helpers below
    // verbatim when building their own "today" windows for other dashboard sections.
    static final ZoneId DASHBOARD_ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private final WalletRepository walletRepository;
    private final TransactionJournalLineRepository transactionJournalLineRepository;
    private final TransactionRepository transactionRepository;
    private final TreasuryRequestRepository treasuryRequestRepository;
    private final AgentRepository agentRepository;

    // Package-private: later tasks reuse this window shape for other "today" dashboard sections.
    record DayWindow(OffsetDateTime start, OffsetDateTime end) {
    }

    // Package-private: later tasks reuse this to compute their own today-window.
    static DayWindow today() {
        OffsetDateTime now = OffsetDateTime.now(DASHBOARD_ZONE);
        OffsetDateTime startOfDay = now.toLocalDate().atStartOfDay(DASHBOARD_ZONE).toOffsetDateTime();
        return new DayWindow(startOfDay, now);
    }

    // Package-private: later tasks add more section-builder methods to this class and a
    // public getOverview() wires them together, but their tests need to call this method
    // directly before getOverview() exists.
    LiquidityCards buildLiquidityCards() {
        BigDecimal totalDebits = transactionJournalLineRepository.sumAmountByEntryType(EntryType.DEBIT);
        BigDecimal totalCredits = transactionJournalLineRepository.sumAmountByEntryType(EntryType.CREDIT);
        // COALESCE(SUM(...), 0) can come back at a different scale than the NUMERIC(19,2)
        // column (e.g. scale-0 literal 0 vs scale-2 0.00); BigDecimal.equals() treats scale
        // as significant, so comparing via compareTo(...) == 0 is required here.
        boolean isLedgerBalanced = totalDebits.compareTo(totalCredits) == 0;

        return new LiquidityCards(
                walletRepository.sumBalanceByWalletType(WalletType.ESCROW_BANK),
                walletRepository.sumBalanceByWalletType(WalletType.COMPANY_MASTER_ACCOUNT),
                walletRepository.sumBalanceByWalletType(WalletType.REVENUE),
                walletRepository.sumBalanceByWalletType(WalletType.AGENT_FLOAT),
                walletRepository.sumBalanceByWalletType(WalletType.AGENT_COMMISSION),
                walletRepository.sumBalanceByWalletType(WalletType.CUSTOMER_MAIN),
                isLedgerBalanced
        );
    }

    // Package-private: Task 6 calls this directly when assembling the final overview response.
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
        long count = 0;
        BigDecimal volume = BigDecimal.ZERO;
        for (TransactionEntity transaction : transactions) {
            if (transaction.getTransactionType().equals(type.name())) {
                count++;
                volume = volume.add(transaction.getAmount());
            }
        }
        return new TransactionTypeMetric(count, volume);
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
            hourlyTrend.add(new HourlyTrendPoint(String.format("%02d:00", hour), volumes[hour], counts[hour]));
        }
        return hourlyTrend;
    }

    // Package-private: Task 6 calls this directly when assembling the final overview response.
    ActionAlerts buildActionAlerts() {
        return new ActionAlerts(buildPendingFloatRequestsSummary(), buildLowFloatAgentsSummary());
    }

    private PendingFloatRequestsSummary buildPendingFloatRequestsSummary() {
        long count = treasuryRequestRepository.countByStatus(RequestStatus.PENDING);
        BigDecimal totalRequestedAmount = treasuryRequestRepository.sumAmountByStatus(RequestStatus.PENDING);
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

        return new LowFloatAgentsSummary(FloatHealth.LOW_THRESHOLD, agents.size(), agents);
    }
}
