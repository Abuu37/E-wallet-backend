package com.application.e_wallet.bank.service;

import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.entity.AgentStatus;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.bank.dto.BankMoveRequest;
import com.application.e_wallet.bank.dto.BankMoveResponse;
import com.application.e_wallet.bank.dto.FloatBankSummary;
import com.application.e_wallet.bank.entity.BankPostingEntity;
import com.application.e_wallet.bank.entity.LinkedBankEntity;
import com.application.e_wallet.bank.repository.BankPostingRepository;
import com.application.e_wallet.common.exception.BadRequestException;
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
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletStatus;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.application.e_wallet.notification.NotificationFormat.tzs;

/**
 * An agent's money moving to and from their own business bank, through Plaid. Float top-up pulls money into escrow
 * and credits the float (no staff approval, capped per day). Sending float or commission to the bank pays out of
 * escrow, with no fee. Each is one transaction with a balanced journal; the caller adds the idempotency check.
 * The matching Sandbox line is posted after commit by {@link BankPostingService}.
 */
@Service
public class AgentBankService {

    private static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    private static final DateTimeFormatter REFERENCE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final AgentRepository agentRepository;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final BankPostingRepository postingRepository;
    private final BankLinkService bankLinks;
    private final BankPostingService postingService;
    private final LedgerService ledgerService;
    private final PasswordEncoder pinEncoder;
    private final Notifier notifier;
    private final BigDecimal dailyCap;

    public AgentBankService(AgentRepository agentRepository, WalletRepository walletRepository, TransactionRepository transactionRepository,
                            BankPostingRepository postingRepository, BankLinkService bankLinks, BankPostingService postingService,
                            LedgerService ledgerService, PasswordEncoder pinEncoder, Notifier notifier,
                            @Value("${agent.float.bank-topup-daily-cap:5000000}") BigDecimal dailyCap) {
        this.agentRepository = agentRepository;
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.postingRepository = postingRepository;
        this.bankLinks = bankLinks;
        this.postingService = postingService;
        this.ledgerService = ledgerService;
        this.pinEncoder = pinEncoder;
        this.notifier = notifier;
        this.dailyCap = dailyCap;
    }

    /** What the Float screen shows: today's bank top-ups against the daily cap. */
    @Transactional(readOnly = true)
    public FloatBankSummary summary(UUID userId) {
        WalletEntity floatWallet = walletOf(userId, WalletType.AGENT_FLOAT);
        BigDecimal used = usedToday(floatWallet.getId());
        return new FloatBankSummary(dailyCap, used, dailyCap.subtract(used).max(BigDecimal.ZERO));
    }

    // ====================================================================== top up float from my bank
    @Transactional
    public BankMoveResponse topUpFloat(BankMoveRequest request, UUID userId) {
        BigDecimal amount = validAmount(request.amount());
        AgentEntity agent = activeAgent(userId);
        UserEntity user = agent.getUser();
        LinkedBankEntity bank = bankLinks.requireUsable(bankLinks.ownBank(userId, request.linkedBankId()));
        requirePin(request.pin(), user, "Wrong PIN. Nothing was added.");

        UUID floatId = walletOf(userId, WalletType.AGENT_FLOAT).getId();
        BigDecimal used = usedToday(floatId);
        if (used.add(amount).compareTo(dailyCap) > 0) {
            BigDecimal left = dailyCap.subtract(used).max(BigDecimal.ZERO);
            throw new BadRequestException("This is over your " + tzs(dailyCap) + " TZS daily limit for bank top-ups. You can still add "
                    + tzs(left) + " TZS by bank today. Use Request float for the rest.");
        }
        // Ask the bank before any wallet is locked, so a slow bank never holds up other payments.
        if (bankLinks.availableTzs(bank).compareTo(amount) < 0) {
            throw new BadRequestException("Your bank balance is not enough for TZS " + tzs(amount) + ".");
        }

        UUID escrowId = escrow().getId();
        Map<UUID, WalletEntity> locked = lock(List.of(floatId, escrowId));
        WalletEntity floatWallet = locked.get(floatId);
        WalletEntity escrow = locked.get(escrowId);
        requireActive(floatWallet);

        escrow.setBalance(escrow.getBalance().add(amount));
        floatWallet.setBalance(floatWallet.getBalance().add(amount));
        ledgerService.postJournal(LedgerTransactionType.FLOAT_BANK_TOPUP, "Float top-up from " + bank.label(), user, List.of(
                new JournalLineRequest(escrowId, EntryType.DEBIT, amount),
                new JournalLineRequest(floatId, EntryType.CREDIT, amount)));
        walletRepository.save(escrow);
        walletRepository.save(floatWallet);

        TransactionEntity transaction = save(TransactionType.FLOAT_BANK_TOPUP, "FBT", escrow, floatWallet, amount, TransactionStatus.COMPLETED, bank);
        queuePosting(transaction, bank, "DEBIT", amount);
        notifier.notify(NotificationKey.A3, NotificationRecipient.of(user), Map.of(
                "amount", tzs(amount), "bank", bankName(bank), "mask", mask(bank), "ref", transaction.getReferenceNo(), "float", tzs(floatWallet.getBalance())));
        return response(transaction, bank, floatWallet);
    }

    // ====================================================================== send float to my bank
    @Transactional
    public BankMoveResponse floatToBank(BankMoveRequest request, UUID userId) {
        return toBank(request, userId, WalletType.AGENT_FLOAT, TransactionType.FLOAT_BANK_WITHDRAWAL, LedgerTransactionType.FLOAT_BANK_WITHDRAW, "FBW", NotificationKey.A7);
    }

    // ====================================================================== send commission to my bank
    @Transactional
    public BankMoveResponse commissionToBank(BankMoveRequest request, UUID userId) {
        return toBank(request, userId, WalletType.AGENT_COMMISSION, TransactionType.COMMISSION_BANK_WITHDRAWAL, LedgerTransactionType.COMMISSION_BANK_WITHDRAW, "CBW", NotificationKey.A8);
    }

    private BankMoveResponse toBank(BankMoveRequest request, UUID userId, WalletType source, TransactionType type, LedgerTransactionType journalType,
                                    String prefix, NotificationKey key) {
        BigDecimal amount = validAmount(request.amount());
        AgentEntity agent = activeAgent(userId);
        UserEntity user = agent.getUser();
        LinkedBankEntity bank = bankLinks.requireUsable(bankLinks.ownBank(userId, request.linkedBankId()));

        UUID sourceId = walletOf(userId, source).getId();
        UUID escrowId = escrow().getId();
        Map<UUID, WalletEntity> locked = lock(List.of(sourceId, escrowId));
        WalletEntity sourceWallet = locked.get(sourceId);
        WalletEntity escrow = locked.get(escrowId);

        requirePin(request.pin(), user, "Wrong PIN. Nothing was sent.");
        requireActive(sourceWallet);
        if (sourceWallet.getBalance().compareTo(amount) < 0) {
            throw new BadRequestException("Not enough in your " + (source == WalletType.AGENT_FLOAT ? "float" : "commission wallet")
                    + ". You have TZS " + tzs(sourceWallet.getBalance()) + ".");
        }

        // The wallet money is gone now; escrow pays the bank. If the bank refuses, a reversal brings it back.
        sourceWallet.setBalance(sourceWallet.getBalance().subtract(amount));
        escrow.setBalance(escrow.getBalance().subtract(amount));
        ledgerService.postJournal(journalType, "Send to " + bank.label(), user, List.of(
                new JournalLineRequest(sourceId, EntryType.DEBIT, amount),
                new JournalLineRequest(escrowId, EntryType.CREDIT, amount)));
        walletRepository.save(sourceWallet);
        walletRepository.save(escrow);

        TransactionEntity transaction = save(type, prefix, sourceWallet, escrow, amount, TransactionStatus.PENDING, bank);
        queuePosting(transaction, bank, "CREDIT", amount);
        Map<String, String> params = new HashMap<>(Map.of("amount", tzs(amount), "bank", bankName(bank), "mask", mask(bank), "ref", transaction.getReferenceNo()));
        if (key == NotificationKey.A7) {
            params.put("float", tzs(sourceWallet.getBalance()));
        }
        notifier.notify(key, NotificationRecipient.of(user), params);
        return response(transaction, bank, sourceWallet);
    }

    // ====================================================================== helpers
    private BigDecimal usedToday(UUID floatWalletId) {
        LocalDate today = LocalDate.now(ZONE);
        BigDecimal used = transactionRepository.sumReceivedByTypeBetween(floatWalletId, TransactionType.FLOAT_BANK_TOPUP.name(),
                today.atStartOfDay(ZONE).toOffsetDateTime(), today.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime());
        return used == null ? BigDecimal.ZERO : used;
    }

    private TransactionEntity save(TransactionType type, String prefix, WalletEntity from, WalletEntity to, BigDecimal amount, TransactionStatus status, LinkedBankEntity bank) {
        return transactionRepository.save(TransactionEntity.builder()
                .referenceNo(referenceNo(prefix)).senderWallet(from).receiverWallet(to)
                .amount(amount).fee(BigDecimal.ZERO).commission(BigDecimal.ZERO)
                .transactionType(type.name()).status(status.name())
                .linkedBankId(bank.getId()).counterpartyLabel(bank.label()).build());
    }

    private void queuePosting(TransactionEntity transaction, LinkedBankEntity bank, String direction, BigDecimal amountTzs) {
        BankPostingEntity posting = postingRepository.save(BankPostingEntity.builder()
                .transactionId(transaction.getId()).linkedBankId(bank.getId()).direction(direction)
                .usdAmount(bankLinks.toBankCurrency(amountTzs)).build());
        postingService.attemptAfterCommit(posting.getId());
    }

    private BankMoveResponse response(TransactionEntity transaction, LinkedBankEntity bank, WalletEntity wallet) {
        return new BankMoveResponse(transaction.getReferenceNo(), transaction.getStatus(), wallet.getCurrency(), transaction.getAmount(), BigDecimal.ZERO,
                transaction.getAmount(), bank.label(), bankLinks.nameCheck(bank), wallet.getBalance(), OffsetDateTime.now());
    }

    private AgentEntity activeAgent(UUID userId) {
        AgentEntity agent = agentRepository.findByUserId(userId).orElseThrow(() -> new BadRequestException("No agent profile for this account"));
        if (agent.getAgentStatus() != AgentStatus.ACTIVE) {
            throw new BadRequestException("Your agent account is not active.");
        }
        return agent;
    }

    private void requireActive(WalletEntity wallet) {
        if (wallet.getStatus() != WalletStatus.ACTIVE) {
            throw new BadRequestException("This wallet is not active.");
        }
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

    private Map<UUID, WalletEntity> lock(List<UUID> ids) {
        Map<UUID, WalletEntity> byId = new HashMap<>();
        for (WalletEntity wallet : walletRepository.lockAllOrdered(ids)) {
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
