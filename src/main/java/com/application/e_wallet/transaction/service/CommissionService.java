package com.application.e_wallet.transaction.service;

import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.ledger.dto.JournalLineRequest;
import com.application.e_wallet.ledger.entity.EntryType;
import com.application.e_wallet.ledger.entity.LedgerTransactionType;
import com.application.e_wallet.ledger.service.LedgerService;
import com.application.e_wallet.transaction.entity.CommissionOwedEntity;
import com.application.e_wallet.transaction.entity.TransactionEntity;
import com.application.e_wallet.transaction.repository.CommissionOwedRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Agents earn commission from the COMMISSION pool. If the pool is empty the transaction still completes: the
 * commission is recorded as owed and paid, oldest first, as soon as the pool is refilled.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionService {

    private final WalletRepository walletRepository;
    private final CommissionOwedRepository owedRepository;
    private final LedgerService ledgerService;

    public boolean poolCanPay(WalletEntity pool, BigDecimal commission) {
        return commission.signum() == 0 || pool.getBalance().compareTo(commission) >= 0;
    }

    /** Called when the pool could not pay at the time of the transaction. */
    public void recordOwed(AgentEntity agent, TransactionEntity transaction, BigDecimal commission) {
        if (commission.signum() <= 0) {
            return;
        }
        owedRepository.save(CommissionOwedEntity.builder().agent(agent).transaction(transaction).amount(commission).build());
        log.info("Commission {} owed to agent {} (pool empty)", commission, agent.getAgentNumber());
    }

    /** After a refill: pay owed commission, oldest first, for as long as the pool can cover the next one. */
    @Transactional
    public int settleOwed(UserEntity actor) {
        List<CommissionOwedEntity> unpaid = owedRepository.findUnpaidOldestFirst();
        if (unpaid.isEmpty()) {
            return 0;
        }
        WalletEntity pool = walletRepository.findByWalletTypeForUpdate(WalletType.COMMISSION)
                .orElseThrow(() -> new IllegalStateException("Commission wallet not initialized"));

        int paid = 0;
        for (CommissionOwedEntity owed : unpaid) {
            if (pool.getBalance().compareTo(owed.getAmount()) < 0) {
                break;
            }
            WalletEntity agentWallet = walletRepository
                    .findByUserIdAndWalletTypeForUpdate(owed.getAgent().getUser().getId(), WalletType.AGENT_COMMISSION)
                    .orElseThrow(() -> new IllegalStateException("Agent commission wallet not found"));

            pool.setBalance(pool.getBalance().subtract(owed.getAmount()));
            agentWallet.setBalance(agentWallet.getBalance().add(owed.getAmount()));
            ledgerService.postJournal(LedgerTransactionType.COMMISSION_SETTLE,
                    "Owed commission paid to " + owed.getAgent().getAgentNumber(), actor, List.of(
                            new JournalLineRequest(pool.getId(), EntryType.DEBIT, owed.getAmount()),
                            new JournalLineRequest(agentWallet.getId(), EntryType.CREDIT, owed.getAmount())));
            walletRepository.save(pool);
            walletRepository.save(agentWallet);

            owed.setPaidAt(OffsetDateTime.now());
            owedRepository.save(owed);
            paid++;
        }
        return paid;
    }
}
