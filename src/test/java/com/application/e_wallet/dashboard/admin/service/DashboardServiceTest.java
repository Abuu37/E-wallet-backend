package com.application.e_wallet.dashboard.admin.service;

import com.application.e_wallet.agent.dto.FloatHealth;
import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.entity.AgentStatus;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.customer.entity.CustomerStatus;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.dashboard.admin.dto.ActionAlerts;
import com.application.e_wallet.dashboard.admin.dto.DashboardOverviewResponse;
import com.application.e_wallet.dashboard.admin.dto.LiquidityCards;
import com.application.e_wallet.dashboard.admin.dto.NetworkStats;
import com.application.e_wallet.dashboard.admin.dto.RecentTransactionItem;
import com.application.e_wallet.dashboard.admin.dto.TodayTransactionMetrics;
import com.application.e_wallet.ledger.entity.EntryType;
import com.application.e_wallet.ledger.repository.TransactionJournalLineRepository;
import com.application.e_wallet.transaction.entity.TransactionEntity;
import com.application.e_wallet.transaction.repository.TransactionRepository;
import com.application.e_wallet.treasury.entity.RequestStatus;
import com.application.e_wallet.treasury.entity.RequestType;
import com.application.e_wallet.treasury.entity.TreasuryRequestEntity;
import com.application.e_wallet.treasury.repository.TreasuryRequestRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {
    @Mock private WalletRepository walletRepository;
    @Mock private TransactionJournalLineRepository transactionJournalLineRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private TreasuryRequestRepository treasuryRequestRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private CustomerRepository customerRepository;
    @InjectMocks private DashboardService dashboardService;

    @Test
    void buildLiquidityCardsSumsEachWalletTypeAndFlagsBalancedLedger() {
        when(walletRepository.sumBalanceByWalletType(WalletType.ESCROW_BANK)).thenReturn(new BigDecimal("50000000.00"));
        when(walletRepository.sumBalanceByWalletType(WalletType.COMPANY_MASTER_ACCOUNT)).thenReturn(new BigDecimal("18500000.00"));
        when(walletRepository.sumBalanceByWalletType(WalletType.REVENUE)).thenReturn(new BigDecimal("1250000.00"));
        when(walletRepository.sumBalanceByWalletType(WalletType.AGENT_FLOAT)).thenReturn(new BigDecimal("19100000.00"));
        when(walletRepository.sumBalanceByWalletType(WalletType.AGENT_COMMISSION)).thenReturn(new BigDecimal("850000.00"));
        when(walletRepository.sumBalanceByWalletType(WalletType.CUSTOMER_MAIN)).thenReturn(new BigDecimal("10300000.00"));
        when(transactionJournalLineRepository.sumAmountByEntryType(EntryType.DEBIT)).thenReturn(new BigDecimal("100000.00"));
        when(transactionJournalLineRepository.sumAmountByEntryType(EntryType.CREDIT)).thenReturn(new BigDecimal("100000.00"));

        LiquidityCards result = dashboardService.buildLiquidityCards();

        assertThat(result.escrowBankBalance()).isEqualByComparingTo("50000000.00");
        assertThat(result.totalCustomerWalletBalance()).isEqualByComparingTo("10300000.00");
        assertThat(result.isLedgerBalanced()).isTrue();
    }

    @Test
    void buildLiquidityCardsFlagsUnbalancedLedger() {
        when(walletRepository.sumBalanceByWalletType(any())).thenReturn(BigDecimal.ZERO);
        when(transactionJournalLineRepository.sumAmountByEntryType(EntryType.DEBIT)).thenReturn(new BigDecimal("100000.00"));
        when(transactionJournalLineRepository.sumAmountByEntryType(EntryType.CREDIT)).thenReturn(new BigDecimal("99999.00"));

        LiquidityCards result = dashboardService.buildLiquidityCards();

        assertThat(result.isLedgerBalanced()).isFalse();
    }

    @Test
    void buildLiquidityCardsTreatsScaleDifferenceAsBalanced() {
        // DEBIT returns scale-0 zero, CREDIT returns scale-2 zero (the COALESCE(...,0) vs NUMERIC(19,2) mismatch
        // the design doc flags) — must still compare equal via compareTo, not equals.
        when(walletRepository.sumBalanceByWalletType(any())).thenReturn(BigDecimal.ZERO);
        when(transactionJournalLineRepository.sumAmountByEntryType(EntryType.DEBIT)).thenReturn(BigDecimal.ZERO);
        when(transactionJournalLineRepository.sumAmountByEntryType(EntryType.CREDIT)).thenReturn(new BigDecimal("0.00"));

        LiquidityCards result = dashboardService.buildLiquidityCards();

        assertThat(result.isLedgerBalanced()).isTrue();
    }

    @Test
    void buildTodayTransactionMetricsSumsOnlyCompletedTransactionsIntoBreakdownAndTotals() {
        DashboardService.DayWindow window = new DashboardService.DayWindow(
                OffsetDateTime.parse("2026-11-16T00:00:00+03:00"), OffsetDateTime.parse("2026-11-16T10:30:00+03:00"));

        TransactionEntity cashIn = transaction("CASH_IN", "COMPLETED", "100.00", "2026-11-16T08:00:00+03:00");
        TransactionEntity cashOut = transaction("CASH_OUT", "COMPLETED", "50.00", "2026-11-16T09:00:00+03:00");

        when(transactionRepository.findByStatusAndCreatedAtBetween("COMPLETED", window.start(), window.end()))
                .thenReturn(List.of(cashIn, cashOut));

        TodayTransactionMetrics result = dashboardService.buildTodayTransactionMetrics(window);

        assertThat(result.totalVolume()).isEqualByComparingTo("150.00");
        assertThat(result.totalCount()).isEqualTo(2);
        assertThat(result.breakdown().cashIn().count()).isEqualTo(1);
        assertThat(result.breakdown().cashIn().volume()).isEqualByComparingTo("100.00");
        assertThat(result.breakdown().cashOut().volume()).isEqualByComparingTo("50.00");
        assertThat(result.breakdown().serviceFee().count()).isZero();
        assertThat(result.breakdown().commission().count()).isZero();
    }

    @Test
    void buildTodayTransactionMetricsBucketsHourlyTrendByLocalHourUpToNow() {
        DashboardService.DayWindow window = new DashboardService.DayWindow(
                OffsetDateTime.parse("2026-11-16T00:00:00+03:00"), OffsetDateTime.parse("2026-11-16T10:30:00+03:00"));

        // Stored as UTC — 2026-11-15T21:30:00Z is 2026-11-16T00:30:00+03:00: today's first half-hour, local bucket "00:00".
        TransactionEntity nearMidnight = transaction("CASH_IN", "COMPLETED", "10.00", "2026-11-15T21:30:00Z");

        when(transactionRepository.findByStatusAndCreatedAtBetween("COMPLETED", window.start(), window.end()))
                .thenReturn(List.of(nearMidnight));

        TodayTransactionMetrics result = dashboardService.buildTodayTransactionMetrics(window);

        assertThat(result.hourlyTrend()).hasSize(11); // "00:00".."10:00" inclusive
        assertThat(result.hourlyTrend().get(0).hour()).isEqualTo("00:00");
        assertThat(result.hourlyTrend().get(0).count()).isEqualTo(1);
        assertThat(result.hourlyTrend().get(0).volume()).isEqualByComparingTo("10.00");
        assertThat(result.hourlyTrend().get(10).hour()).isEqualTo("10:00");
        assertThat(result.hourlyTrend().get(10).count()).isZero();
    }

    @Test
    void buildTodayTransactionMetricsHandlesNoTransactionsToday() {
        DashboardService.DayWindow window = new DashboardService.DayWindow(
                OffsetDateTime.parse("2026-11-16T00:00:00+03:00"), OffsetDateTime.parse("2026-11-16T00:00:00+03:00"));

        when(transactionRepository.findByStatusAndCreatedAtBetween("COMPLETED", window.start(), window.end()))
                .thenReturn(List.of());

        TodayTransactionMetrics result = dashboardService.buildTodayTransactionMetrics(window);

        assertThat(result.totalVolume()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.totalCount()).isZero();
        assertThat(result.hourlyTrend()).hasSize(1); // just "00:00"
    }

    @Test
    void buildActionAlertsReportsFullPendingTotalsButCapsRecentRequestsListAtTen() {
        when(treasuryRequestRepository.countByStatus(RequestStatus.PENDING)).thenReturn(15L);
        when(treasuryRequestRepository.sumAmountByStatus(RequestStatus.PENDING)).thenReturn(new BigDecimal("2500000.00"));
        TreasuryRequestEntity request = pendingRequest(
                "45", "40512", "Kariakoo Wakala Point", "500000.00", "CI261116.0830.A11223", RequestType.WITHDRAWAL);
        when(treasuryRequestRepository.findTop10ByStatusOrderByCreatedAtDesc(RequestStatus.PENDING)).thenReturn(List.of(request));
        when(walletRepository.findByUserIdAndWalletType(request.getAgent().getUser().getId(), WalletType.AGENT_FLOAT))
                .thenReturn(Optional.of(WalletEntity.builder().accountNumber("018492019482").build()));
        stubNoLowFloatAgents();

        ActionAlerts result = dashboardService.buildActionAlerts();

        assertThat(result.pendingFloatRequests().count()).isEqualTo(15);
        assertThat(result.pendingFloatRequests().totalRequestedAmount()).isEqualByComparingTo("2500000.00");
        assertThat(result.pendingFloatRequests().recentRequests()).hasSize(1);
        assertThat(result.pendingFloatRequests().recentRequests().get(0).floatAccountNumber()).isEqualTo("018492019482");
        assertThat(result.pendingFloatRequests().recentRequests().get(0).requestType()).isEqualTo(RequestType.WITHDRAWAL);
    }

    @Test
    void buildActionAlertsThrowsWhenPendingRequestAgentHasNoFloatWallet() {
        when(treasuryRequestRepository.countByStatus(RequestStatus.PENDING)).thenReturn(1L);
        when(treasuryRequestRepository.sumAmountByStatus(RequestStatus.PENDING)).thenReturn(new BigDecimal("500000.00"));
        TreasuryRequestEntity request = pendingRequest("46", "40513", "Mbezi Wakala Point", "500000.00", "CI261116.0900.A11224");
        when(treasuryRequestRepository.findTop10ByStatusOrderByCreatedAtDesc(RequestStatus.PENDING)).thenReturn(List.of(request));
        when(walletRepository.findByUserIdAndWalletType(request.getAgent().getUser().getId(), WalletType.AGENT_FLOAT))
                .thenReturn(Optional.empty());

        assertThatThrownBy(dashboardService::buildActionAlerts)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Agent float wallet not found");
    }

    @Test
    void buildActionAlertsPassesLowThresholdToRepositoryQuery() {
        stubNoPendingRequests();
        when(agentRepository.findLowFloatAgents(WalletType.AGENT_FLOAT, FloatHealth.LOW_THRESHOLD, AgentStatus.ACTIVE))
                .thenReturn(List.of()); // the repository query itself enforces strict "<"; nothing at/above the threshold is ever returned

        ActionAlerts result = dashboardService.buildActionAlerts();

        assertThat(result.lowFloatAgents().count()).isZero();
        assertThat(result.lowFloatAgents().threshold()).isEqualByComparingTo(FloatHealth.LOW_THRESHOLD);
    }

    @Test
    void buildActionAlertsHandlesNoPendingRequestsAndNoLowFloatAgents() {
        stubNoPendingRequests();
        stubNoLowFloatAgents();

        ActionAlerts result = dashboardService.buildActionAlerts();

        assertThat(result.pendingFloatRequests().count()).isZero();
        assertThat(result.pendingFloatRequests().recentRequests()).isEmpty();
        assertThat(result.lowFloatAgents().agents()).isEmpty();
    }

    @Test
    void buildNetworkStatsCountsActiveAgentsAndCustomersAndSplitsPendingAndFailedTransactions() {
        DashboardService.DayWindow window = new DashboardService.DayWindow(
                OffsetDateTime.parse("2026-11-16T00:00:00+03:00"), OffsetDateTime.parse("2026-11-16T10:30:00+03:00"));

        when(agentRepository.countByAgentStatus(AgentStatus.ACTIVE)).thenReturn(85L);
        when(customerRepository.countByStatus(CustomerStatus.ACTIVE)).thenReturn(1420L);
        when(transactionRepository.findByStatusAndCreatedAtBetween("PENDING", window.start(), window.end()))
                .thenReturn(List.of(transaction("CASH_IN", "PENDING", "30000.00", "2026-11-16T09:00:00+03:00")));
        when(transactionRepository.findByStatusAndCreatedAtBetween("FAILED", window.start(), window.end()))
                .thenReturn(List.of());

        NetworkStats result = dashboardService.buildNetworkStats(window);

        assertThat(result.totalActiveAgents()).isEqualTo(85);
        assertThat(result.totalActiveCustomers()).isEqualTo(1420);
        assertThat(result.pendingTransactionsToday().count()).isEqualTo(1);
        assertThat(result.pendingTransactionsToday().volume()).isEqualByComparingTo("30000.00");
        assertThat(result.failedTransactionsToday().count()).isZero();
        assertThat(result.failedTransactionsToday().volume()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void getOverviewAssemblesAllFourSectionsWithFixedCurrencyAndZoneTimestamp() {
        // Stub every repository call each buildXxx method needs (same stubs as their individual tests,
        // combined) so getOverview() runs end-to-end without NPEs.

        // buildLiquidityCards()
        when(walletRepository.sumBalanceByWalletType(any())).thenReturn(BigDecimal.ZERO);
        when(transactionJournalLineRepository.sumAmountByEntryType(EntryType.DEBIT)).thenReturn(new BigDecimal("100000.00"));
        when(transactionJournalLineRepository.sumAmountByEntryType(EntryType.CREDIT)).thenReturn(new BigDecimal("100000.00"));

        // buildTodayTransactionMetrics(window) and buildNetworkStats(window)
        when(transactionRepository.findByStatusAndCreatedAtBetween(eq("COMPLETED"), any(), any())).thenReturn(List.of());
        when(transactionRepository.findByStatusAndCreatedAtBetween(eq("PENDING"), any(), any())).thenReturn(List.of());
        when(transactionRepository.findByStatusAndCreatedAtBetween(eq("FAILED"), any(), any())).thenReturn(List.of());

        // buildActionAlerts()
        stubNoPendingRequests();
        stubNoLowFloatAgents();

        // buildNetworkStats(window)
        when(agentRepository.countByAgentStatus(AgentStatus.ACTIVE)).thenReturn(85L);
        when(customerRepository.countByStatus(CustomerStatus.ACTIVE)).thenReturn(1420L);

        // buildRecentTransactions()
        when(transactionRepository.findRecentWithWallets(any())).thenReturn(List.of());

        DashboardOverviewResponse result = dashboardService.getOverview();

        assertThat(result.currency()).isEqualTo("TZS");
        assertThat(result.timestamp().getOffset()).isEqualTo(ZoneOffset.ofHours(3));
        assertThat(result.liquidityCards()).isNotNull();
        assertThat(result.todayTransactionMetrics()).isNotNull();
        assertThat(result.actionAlerts()).isNotNull();
        assertThat(result.networkStats()).isNotNull();
        assertThat(result.recentTransactions()).isNotNull();
    }

    @Test
    void buildRecentTransactionsMapsTop10MostRecentTransactionsAcrossAllTypesAndStatuses() {
        WalletEntity clearingWallet = WalletEntity.builder()
                .accountName("Payment Gateway Clearing").accountNumber("COMPANY-CLEARING-ACCOUNT").build();
        WalletEntity customerWallet = WalletEntity.builder()
                .accountName("Jane Doe").accountNumber("0123456789").build();
        TransactionEntity stripeTopup = transaction("TOPUP_STRIPE", "PENDING", "5000.00", "2026-11-22T12:00:00+03:00");
        stripeTopup.setReferenceNo("TXN-20261122-ABCD1234");
        stripeTopup.setSenderWallet(clearingWallet);
        stripeTopup.setReceiverWallet(customerWallet);
        TransactionEntity cashOut = transaction("CASH_OUT", "COMPLETED", "300.00", "2026-11-22T11:00:00+03:00");
        cashOut.setReferenceNo("TXN-20261122-EFGH5678");
        cashOut.setSenderWallet(customerWallet);
        cashOut.setReceiverWallet(clearingWallet);

        when(transactionRepository.findRecentWithWallets(any())).thenReturn(List.of(stripeTopup, cashOut));

        List<RecentTransactionItem> result = dashboardService.buildRecentTransactions();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).referenceNo()).isEqualTo("TXN-20261122-ABCD1234");
        assertThat(result.get(0).transactionType()).isEqualTo("TOPUP_STRIPE");
        assertThat(result.get(0).status()).isEqualTo("PENDING");
        assertThat(result.get(0).amount()).isEqualByComparingTo("5000.00");
        assertThat(result.get(0).fromAccountName()).isEqualTo("Payment Gateway Clearing");
        assertThat(result.get(0).fromAccountNumber()).isEqualTo("COMPANY-CLEARING-ACCOUNT");
        assertThat(result.get(0).toAccountName()).isEqualTo("Jane Doe");
        assertThat(result.get(0).toAccountNumber()).isEqualTo("0123456789");
        assertThat(result.get(1).referenceNo()).isEqualTo("TXN-20261122-EFGH5678");
    }

    @Test
    void buildRecentTransactionsHandlesNoTransactionsYet() {
        when(transactionRepository.findRecentWithWallets(any())).thenReturn(List.of());

        assertThat(dashboardService.buildRecentTransactions()).isEmpty();
    }

    private static TransactionEntity transaction(String type, String status, String amount, String createdAtIso) {
        return TransactionEntity.builder()
                .transactionType(type)
                .status(status)
                .amount(new BigDecimal(amount))
                .createdAt(OffsetDateTime.parse(createdAtIso))
                .build();
    }

    // idSeed deterministically derives the request's id so fixtures stay stable/debuggable
    // across test runs without needing to thread an explicit UUID through every call site.
    private static TreasuryRequestEntity pendingRequest(
            String idSeed, String agentCode, String businessName, String amount, String externalReference) {
        return pendingRequest(idSeed, agentCode, businessName, amount, externalReference, RequestType.TOPUP);
    }

    private static TreasuryRequestEntity pendingRequest(
            String idSeed, String agentCode, String businessName, String amount, String externalReference,
            RequestType requestType) {
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).build();
        AgentEntity agent = AgentEntity.builder()
                .id(UUID.randomUUID())
                .user(user)
                .agentNumber(agentCode)
                .businessName(businessName)
                .build();

        return TreasuryRequestEntity.builder()
                .id(UUID.nameUUIDFromBytes(idSeed.getBytes()))
                .agent(agent)
                .amount(new BigDecimal(amount))
                .externalReference(externalReference)
                .requestType(requestType)
                .status(RequestStatus.PENDING)
                .createdAt(OffsetDateTime.now())
                .build();
    }

    private void stubNoPendingRequests() {
        when(treasuryRequestRepository.countByStatus(RequestStatus.PENDING)).thenReturn(0L);
        when(treasuryRequestRepository.sumAmountByStatus(RequestStatus.PENDING)).thenReturn(BigDecimal.ZERO);
        when(treasuryRequestRepository.findTop10ByStatusOrderByCreatedAtDesc(RequestStatus.PENDING)).thenReturn(List.of());
    }

    private void stubNoLowFloatAgents() {
        when(agentRepository.findLowFloatAgents(WalletType.AGENT_FLOAT, FloatHealth.LOW_THRESHOLD, AgentStatus.ACTIVE))
                .thenReturn(List.of());
    }
}
