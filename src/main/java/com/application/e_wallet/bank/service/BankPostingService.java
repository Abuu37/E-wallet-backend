package com.application.e_wallet.bank.service;

import com.application.e_wallet.bank.entity.BankPostingEntity;
import com.application.e_wallet.bank.entity.LinkedBankEntity;
import com.application.e_wallet.bank.repository.BankPostingRepository;
import com.application.e_wallet.bank.repository.LinkedBankRepository;
import com.application.e_wallet.integration.plaid.PlaidBankApi;
import com.application.e_wallet.ledger.dto.JournalLineRequest;
import com.application.e_wallet.ledger.entity.EntryType;
import com.application.e_wallet.ledger.entity.LedgerTransactionType;
import com.application.e_wallet.ledger.service.LedgerService;
import com.application.e_wallet.notification.NotificationKey;
import com.application.e_wallet.notification.NotificationRecipient;
import com.application.e_wallet.notification.Notifier;
import com.application.e_wallet.transaction.entity.TransactionEntity;
import com.application.e_wallet.transaction.entity.TransactionStatus;
import com.application.e_wallet.transaction.entity.TransactionType;
import com.application.e_wallet.transaction.repository.TransactionRepository;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.application.e_wallet.notification.NotificationFormat.tzs;

/**
 * Puts the matching line on the person's Sandbox bank after a wallet movement has committed.
 * A bank top-up has already credited the wallet, so a failed call is only retried and never undoes the credit.
 * A bank withdrawal stays PENDING until the bank line is posted; if the bank keeps refusing, the amount and fee go
 * back to the wallet with a reversal journal and the withdrawal is marked FAILED.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankPostingService {

    static final int MAX_WITHDRAWAL_ATTEMPTS = 3;
    static final int MAX_TOPUP_ATTEMPTS = 10;

    private final BankPostingRepository postings;
    private final LinkedBankRepository banks;
    private final TransactionRepository transactions;
    private final WalletRepository wallets;
    private final LedgerService ledgerService;
    private final PlaidBankApi plaid;
    private final BankLinkService bankLinks;
    private final Notifier notifier;

    /** Try the posting once the surrounding transaction has committed, off the request thread. */
    public void attemptAfterCommit(UUID postingId) {
        Runnable run = () -> CompletableFuture.runAsync(() -> {
            try {
                self().attempt(postingId);
            } catch (RuntimeException e) {
                log.warn("Bank posting {} could not run: {}", postingId, e.getClass().getSimpleName());
            }
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    run.run();
                }
            });
        } else {
            run.run();
        }
    }

    /** Anything still pending after a moment is tried again. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 20_000)
    public void retryPending() {
        for (UUID id : postings.findPendingOlderThan(OffsetDateTime.now().minusSeconds(20))) {
            try {
                self().attempt(id);
            } catch (RuntimeException e) {
                log.warn("Bank posting {} retry failed: {}", id, e.getClass().getSimpleName());
            }
        }
    }

    @Transactional
    public void attempt(UUID postingId) {
        BankPostingEntity posting = postings.lockById(postingId).orElse(null);
        if (posting == null || !"PENDING".equals(posting.getStatus())) {
            return; // someone else finished it
        }
        LinkedBankEntity bank = banks.findById(posting.getLinkedBankId()).orElseThrow();
        TransactionEntity transaction = transactions.findById(posting.getTransactionId()).orElseThrow();
        boolean credit = "CREDIT".equals(posting.getDirection());
        try {
            plaid.postSandboxLine(bankLinks.accessToken(bank), credit ? posting.getUsdAmount().negate() : posting.getUsdAmount(),
                    "WalletPesa " + transaction.getReferenceNo());
            posting.setStatus("DONE");
            posting.setDoneAt(OffsetDateTime.now());
            if (isWithdrawal(transaction) && TransactionStatus.PENDING.name().equals(transaction.getStatus())) {
                transaction.setStatus(TransactionStatus.COMPLETED.name());
                transactions.save(transaction);
            }
        } catch (RuntimeException e) {
            posting.setAttempts(posting.getAttempts() + 1);
            posting.setLastError(e.getClass().getSimpleName());
            log.warn("Bank posting {} attempt {} failed: {}", postingId, posting.getAttempts(), e.getClass().getSimpleName());
            if (isWithdrawal(transaction) && posting.getAttempts() >= MAX_WITHDRAWAL_ATTEMPTS) {
                posting.setStatus("FAILED");
                reverseWithdrawal(transaction, bank);
            } else if (!isWithdrawal(transaction) && posting.getAttempts() >= MAX_TOPUP_ATTEMPTS) {
                posting.setStatus("FAILED");
                log.error("Bank posting {} gave up; the wallet credit stands", postingId);
            }
        }
        postings.save(posting);
    }

    /** The bank refused: a new opposite journal returns the amount and the fee to the wallet. Nothing is edited or deleted. */
    private void reverseWithdrawal(TransactionEntity transaction, LinkedBankEntity bank) {
        UUID customerWalletId = transaction.getSenderWallet().getId();
        UUID escrowId = transaction.getReceiverWallet().getId();
        BigDecimal fee = transaction.getFee();
        UUID revenueId = fee.signum() > 0 ? wallets.findByWalletType(WalletType.REVENUE).orElseThrow().getId() : null;
        List<UUID> ids = new ArrayList<>(List.of(customerWalletId, escrowId));
        if (revenueId != null) {
            ids.add(revenueId);
        }
        Map<UUID, WalletEntity> locked = new HashMap<>();
        for (WalletEntity w : wallets.lockAllOrdered(ids)) {
            locked.put(w.getId(), w);
        }
        WalletEntity customer = locked.get(customerWalletId);
        WalletEntity escrow = locked.get(escrowId);
        WalletEntity revenue = revenueId == null ? null : locked.get(revenueId);

        BigDecimal amount = transaction.getAmount();
        customer.setBalance(customer.getBalance().add(amount).add(fee));
        escrow.setBalance(escrow.getBalance().add(amount));
        List<JournalLineRequest> lines = new ArrayList<>();
        lines.add(new JournalLineRequest(customerWalletId, EntryType.CREDIT, amount.add(fee)));
        lines.add(new JournalLineRequest(escrowId, EntryType.DEBIT, amount));
        if (fee.signum() > 0) {
            revenue.setBalance(revenue.getBalance().subtract(fee));
            lines.add(new JournalLineRequest(revenueId, EntryType.DEBIT, fee));
        }
        ledgerService.postJournal(LedgerTransactionType.BANK_WITHDRAW_REVERSAL, "Reversal of failed bank withdrawal " + transaction.getReferenceNo(),
                customer.getUser(), lines);
        wallets.save(customer);
        wallets.save(escrow);
        if (revenue != null) {
            wallets.save(revenue);
        }

        transaction.setStatus(TransactionStatus.FAILED.name());
        transactions.save(transaction);

        boolean isCustomer = customer.getWalletType() == WalletType.CUSTOMER_MAIN;
        notifier.notify(isCustomer ? NotificationKey.C7 : NotificationKey.A10, NotificationRecipient.of(customer.getUser()), Map.of(
                "amount", tzs(amount), "bank", bank.getInstitutionName() == null ? "your bank" : bank.getInstitutionName(),
                "mask", bank.getAccountMask() == null ? "" : bank.getAccountMask(), "ref", transaction.getReferenceNo()));
    }

    private static boolean isWithdrawal(TransactionEntity transaction) {
        String type = transaction.getTransactionType();
        return TransactionType.BANK_WITHDRAWAL.name().equals(type) || TransactionType.FLOAT_BANK_WITHDRAWAL.name().equals(type)
                || TransactionType.COMMISSION_BANK_WITHDRAWAL.name().equals(type);
    }

    // The proxy, so @Transactional applies when this class calls itself from another thread.
    private BankPostingService self;

    @org.springframework.beans.factory.annotation.Autowired
    void setSelf(@org.springframework.context.annotation.Lazy BankPostingService self) {
        this.self = self;
    }

    private BankPostingService self() {
        return self;
    }
}
