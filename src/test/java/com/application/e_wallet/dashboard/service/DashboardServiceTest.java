package com.application.e_wallet.dashboard.service;

import com.application.e_wallet.dashboard.dto.LiquidityCards;
import com.application.e_wallet.ledger.entity.EntryType;
import com.application.e_wallet.ledger.repository.TransactionJournalLineRepository;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {
    @Mock private WalletRepository walletRepository;
    @Mock private TransactionJournalLineRepository transactionJournalLineRepository;
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
}
