package com.application.e_wallet.bank.service;

import com.application.e_wallet.bank.dto.BankMoveRequest;
import com.application.e_wallet.bank.dto.BankMoveResponse;
import com.application.e_wallet.bank.entity.BankPostingEntity;
import com.application.e_wallet.bank.entity.LinkedBankEntity;
import com.application.e_wallet.bank.repository.BankPostingRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.customer.entity.CustomerStatus;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.ledger.dto.JournalLineRequest;
import com.application.e_wallet.ledger.entity.EntryType;
import com.application.e_wallet.ledger.entity.LedgerTransactionType;
import com.application.e_wallet.ledger.service.LedgerService;
import com.application.e_wallet.limits.LimitService;
import com.application.e_wallet.notification.NotificationKey;
import com.application.e_wallet.notification.NotificationRecipient;
import com.application.e_wallet.notification.Notifier;
import com.application.e_wallet.tariff.service.TariffService;
import com.application.e_wallet.transaction.entity.TransactionEntity;
import com.application.e_wallet.transaction.entity.TransactionStatus;
import com.application.e_wallet.transaction.entity.TransactionType;
import com.application.e_wallet.transaction.repository.TransactionRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletStatus;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.application.e_wallet.notification.NotificationFormat.tzs;

/**
 * A customer adds money from, or withdraws to, their own linked bank. Each is one transaction: lock the wallets in
 * id order, check PIN, status, balance and limits, move the balances, post a balanced journal against the escrow
 * account, write the transactions row. The caller wraps it in the idempotency check, so a retry never posts twice.
 * The matching Sandbox bank line is posted after commit by {@link BankPostingService}.
 */
@Service
@RequiredArgsConstructor
public class BankMoneyService {

    private static final DateTimeFormatter REFERENCE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final CustomerRepository customerRepository;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final BankPostingRepository postingRepository;
    private final BankLinkService bankLinks;
    private final BankPostingService postingService;
    private final TariffService tariffService;
    private final LedgerService ledgerService;
    private final LimitService limitService;
    private final PasswordEncoder pinEncoder;
    private final Notifier notifier;

    // ====================================================================== add money from my bank
    @Transactional
    public BankMoveResponse topUp(BankMoveRequest request, UUID userId) {
        BigDecimal amount = validAmount(request.amount());
        CustomerEntity customer = customerRepository.findByUserId(userId).orElseThrow(() -> new BadRequestException("Customer account not found"));
        UserEntity user = customer.getUser();
        LinkedBankEntity bank = bankLinks.requireUsable(bankLinks.ownBank(userId, request.linkedBankId()));

        requirePin(request.pin(), user, "Wrong PIN. Nothing was added.");
        if (customer.getStatus() != CustomerStatus.ACTIVE) {
            throw new BadRequestException("Your account is not active.");
        }
        limitService.checkPerPayment(customer.getKycStatus(), amount);
        // Ask the bank before any wallet is locked, so a slow bank never holds up other payments.
        if (bankLinks.availableTzs(bank).compareTo(amount) < 0) {
            throw new BadRequestException("Your bank balance is not enough for TZS " + tzs(amount) + ".");
        }

        UUID customerWalletId = walletOf(userId, WalletType.CUSTOMER_MAIN).getId();
        UUID escrowId = escrow().getId();
        Map<UUID, WalletEntity> locked = lock(customerWalletId, escrowId);
        WalletEntity customerWallet = locked.get(customerWalletId);
        WalletEntity escrow = locked.get(escrowId);
        if (customerWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new BadRequestException("Your wallet is not active.");
        }
        limitService.checkReceive(customerWallet.getBalance(), customer.getKycStatus(), amount);

        // Real money reaches escrow, so escrow and the wallet both go up and the books stay equal.
        escrow.setBalance(escrow.getBalance().add(amount));
        customerWallet.setBalance(customerWallet.getBalance().add(amount));
        ledgerService.postJournal(LedgerTransactionType.BANK_TOPUP, "Add money from " + bank.label(), user, List.of(
                new JournalLineRequest(escrowId, EntryType.DEBIT, amount),
                new JournalLineRequest(customerWalletId, EntryType.CREDIT, amount)));
        walletRepository.save(escrow);
        walletRepository.save(customerWallet);

        TransactionEntity transaction = transactionRepository.save(TransactionEntity.builder()
                .referenceNo(referenceNo("BTU")).senderWallet(escrow).receiverWallet(customerWallet)
                .amount(amount).fee(BigDecimal.ZERO).commission(BigDecimal.ZERO)
                .transactionType(TransactionType.BANK_TOPUP.name()).status(TransactionStatus.COMPLETED.name())
                .linkedBankId(bank.getId()).counterpartyLabel(bank.label()).build());
        queuePosting(transaction, bank, "DEBIT", amount);

        notifier.notify(NotificationKey.C1, NotificationRecipient.of(user), Map.of(
                "amount", tzs(amount), "bank", bankName(bank), "mask", mask(bank),
                "ref", transaction.getReferenceNo(), "balance", tzs(customerWallet.getBalance())));

        return response(transaction, BigDecimal.ZERO, bank, customerWallet);
    }

    // ====================================================================== withdraw to my bank
    @Transactional
    public BankMoveResponse withdraw(BankMoveRequest request, UUID userId) {
        BigDecimal amount = validAmount(request.amount());
        CustomerEntity customer = customerRepository.findByUserId(userId).orElseThrow(() -> new BadRequestException("Customer account not found"));
        UserEntity user = customer.getUser();
        LinkedBankEntity bank = bankLinks.requireUsable(bankLinks.ownBank(userId, request.linkedBankId()));

        BigDecimal fee = tariffService.findApplicableTariff(TransactionType.BANK_WITHDRAWAL.name(), amount).getFee();
        BigDecimal total = amount.add(fee);

        UUID customerWalletId = walletOf(userId, WalletType.CUSTOMER_MAIN).getId();
        UUID escrowId = escrow().getId();
        UUID revenueId = walletRepository.findByWalletType(WalletType.REVENUE)
                .orElseThrow(() -> new IllegalStateException("Revenue wallet not initialized")).getId();
        Map<UUID, WalletEntity> locked = lock(customerWalletId, escrowId, revenueId);
        WalletEntity customerWallet = locked.get(customerWalletId);
        WalletEntity escrow = locked.get(escrowId);
        WalletEntity revenue = locked.get(revenueId);

        requirePin(request.pin(), user, "Wrong PIN. Nothing was withdrawn.");
        if (customer.getStatus() != CustomerStatus.ACTIVE) {
            throw new BadRequestException("Your account is not active.");
        }
        if (customerWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new BadRequestException("Your wallet is not active.");
        }
        if (customerWallet.getBalance().compareTo(total) < 0) {
            throw new BadRequestException("Not enough balance. You need TZS " + tzs(total) + " including the fee.");
        }
        limitService.checkSend(customerWalletId, customer.getKycStatus(), amount);

        // The wallet money is gone now; escrow pays the bank. If the bank refuses, a reversal brings it back.
        customerWallet.setBalance(customerWallet.getBalance().subtract(total));
        escrow.setBalance(escrow.getBalance().subtract(amount));
        revenue.setBalance(revenue.getBalance().add(fee));
        List<JournalLineRequest> lines = new ArrayList<>();
        lines.add(new JournalLineRequest(customerWalletId, EntryType.DEBIT, total));
        lines.add(new JournalLineRequest(escrowId, EntryType.CREDIT, amount));
        if (fee.signum() > 0) {
            lines.add(new JournalLineRequest(revenueId, EntryType.CREDIT, fee));
        }
        ledgerService.postJournal(LedgerTransactionType.BANK_WITHDRAW, "Withdraw to " + bank.label(), user, lines);
        walletRepository.save(customerWallet);
        walletRepository.save(escrow);
        walletRepository.save(revenue);

        TransactionEntity transaction = transactionRepository.save(TransactionEntity.builder()
                .referenceNo(referenceNo("BWD")).senderWallet(customerWallet).receiverWallet(escrow)
                .amount(amount).fee(fee).commission(BigDecimal.ZERO)
                .transactionType(TransactionType.BANK_WITHDRAWAL.name()).status(TransactionStatus.PENDING.name())
                .linkedBankId(bank.getId()).counterpartyLabel(bank.label()).build());
        queuePosting(transaction, bank, "CREDIT", amount);

        notifier.notify(NotificationKey.C6, NotificationRecipient.of(user), Map.of(
                "amount", tzs(amount), "bank", bankName(bank), "mask", mask(bank), "fee", tzs(fee),
                "ref", transaction.getReferenceNo(), "balance", tzs(customerWallet.getBalance())));

        return response(transaction, fee, bank, customerWallet);
    }

    // ====================================================================== helpers
    private void queuePosting(TransactionEntity transaction, LinkedBankEntity bank, String direction, BigDecimal amountTzs) {
        BankPostingEntity posting = postingRepository.save(BankPostingEntity.builder()
                .transactionId(transaction.getId()).linkedBankId(bank.getId()).direction(direction)
                .usdAmount(bankLinks.toBankCurrency(amountTzs)).build());
        postingService.attemptAfterCommit(posting.getId());
    }

    private BankMoveResponse response(TransactionEntity transaction, BigDecimal fee, LinkedBankEntity bank, WalletEntity customerWallet) {
        return new BankMoveResponse(transaction.getReferenceNo(), transaction.getStatus(), customerWallet.getCurrency(), transaction.getAmount(), fee,
                transaction.getAmount().add(fee), bank.label(), bankLinks.nameCheck(bank), customerWallet.getBalance(), OffsetDateTime.now());
    }

    private static String bankName(LinkedBankEntity bank) {
        return bank.getInstitutionName() == null ? "your bank" : bank.getInstitutionName();
    }

    private static String mask(LinkedBankEntity bank) {
        return bank.getAccountMask() == null ? "" : bank.getAccountMask();
    }

    private BigDecimal validAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BadRequestException("Amount must be greater than zero.");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new BadRequestException("Amount must have at most 2 decimal places.");
        }
        return amount;
    }

    private WalletEntity walletOf(UUID userId, WalletType type) {
        return walletRepository.findByUserIdAndWalletType(userId, type).orElseThrow(() -> new BadRequestException("Wallet not found"));
    }

    private WalletEntity escrow() {
        return walletRepository.findByWalletType(WalletType.ESCROW_BANK).orElseThrow(() -> new IllegalStateException("Escrow bank wallet not initialized"));
    }

    private Map<UUID, WalletEntity> lock(UUID... ids) {
        Map<UUID, WalletEntity> byId = new HashMap<>();
        for (WalletEntity wallet : walletRepository.lockAllOrdered(List.of(ids))) {
            byId.put(wallet.getId(), wallet);
        }
        return byId;
    }

    private void requirePin(String pin, UserEntity user, String message) {
        if (!pinEncoder.matches(pin, user.getPinHash())) {
            throw new BadRequestException(message);
        }
    }

    private String referenceNo(String prefix) {
        String reference;
        do {
            reference = prefix + "-" + OffsetDateTime.now().format(REFERENCE_DATE_FORMAT) + "-"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        } while (transactionRepository.existsByReferenceNo(reference));
        return reference;
    }
}
