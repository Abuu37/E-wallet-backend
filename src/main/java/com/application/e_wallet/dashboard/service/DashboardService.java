package com.application.e_wallet.dashboard.service;

import com.application.e_wallet.dashboard.dto.LiquidityCards;
import com.application.e_wallet.ledger.entity.EntryType;
import com.application.e_wallet.ledger.repository.TransactionJournalLineRepository;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final WalletRepository walletRepository;
    private final TransactionJournalLineRepository transactionJournalLineRepository;

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
}
