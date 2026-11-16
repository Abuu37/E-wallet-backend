package com.application.e_wallet.dashboard.service;

import com.application.e_wallet.dashboard.dto.LiquidityCards;
import com.application.e_wallet.dashboard.dto.TodayTransactionMetrics;
import com.application.e_wallet.ledger.entity.EntryType;
import com.application.e_wallet.ledger.repository.TransactionJournalLineRepository;
import com.application.e_wallet.transaction.entity.TransactionEntity;
import com.application.e_wallet.transaction.repository.TransactionRepository;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {
    @Mock private WalletRepository walletRepository;
    @Mock private TransactionJournalLineRepository transactionJournalLineRepository;
    @Mock private TransactionRepository transactionRepository;
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
        assertThat(result.breakdown().internalTransfer().count()).isZero();
        assertThat(result.breakdown().commissionTransfer().count()).isZero();
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

    private static TransactionEntity transaction(String type, String status, String amount, String createdAtIso) {
        return TransactionEntity.builder()
                .transactionType(type)
                .status(status)
                .amount(new BigDecimal(amount))
                .createdAt(OffsetDateTime.parse(createdAtIso))
                .build();
    }
}
