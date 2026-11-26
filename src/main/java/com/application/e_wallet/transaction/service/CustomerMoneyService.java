package com.application.e_wallet.transaction.service;

import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.entity.AgentStatus;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.common.validation.PhoneNumberNormalizer;
import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.customer.entity.CustomerStatus;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.ledger.dto.JournalLineRequest;
import com.application.e_wallet.ledger.entity.EntryType;
import com.application.e_wallet.ledger.entity.LedgerTransactionType;
import com.application.e_wallet.ledger.service.LedgerService;
import com.application.e_wallet.limits.LimitService;
import com.application.e_wallet.notification.NotificationFormat;
import com.application.e_wallet.notification.NotificationKey;
import com.application.e_wallet.notification.NotificationRecipient;
import com.application.e_wallet.notification.Notifier;
import com.application.e_wallet.tariff.entity.TariffEntity;
import com.application.e_wallet.tariff.service.TariffService;
import com.application.e_wallet.transaction.dto.TransferRequest;
import com.application.e_wallet.transaction.dto.TransferResponse;
import com.application.e_wallet.transaction.dto.WithdrawRequest;
import com.application.e_wallet.transaction.dto.WithdrawResponse;
import com.application.e_wallet.transaction.entity.TransactionEntity;
import com.application.e_wallet.transaction.entity.TransactionStatus;
import com.application.e_wallet.transaction.entity.TransactionType;
import com.application.e_wallet.transaction.repository.TransactionRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.repository.UserRepository;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletStatus;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import com.application.e_wallet.wallet.service.BeneficiaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.application.e_wallet.notification.NotificationFormat.tzs;

/**
 * The two money flows a customer starts with their own PIN: send to another WalletPesa wallet, and withdraw cash
 * at an agent. Each is one transaction: lock the wallets in id order, check PIN, statuses, balance and limits,
 * move the balances, post a balanced journal, write the transactions row. The caller wraps it in the
 * idempotency check, so a retry never posts twice.
 */
@Service
@RequiredArgsConstructor
public class CustomerMoneyService {

    static final String NOT_ON_WALLETPESA = "This number is not on WalletPesa yet.";

    private static final DateTimeFormatter REFERENCE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CustomerRepository customerRepository;
    private final UserRepository userRepository;
    private final AgentRepository agentRepository;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final TariffService tariffService;
    private final LedgerService ledgerService;
    private final LimitService limitService;
    private final CommissionService commissionService;
    private final BeneficiaryService beneficiaryService;
    private final PasswordEncoder pinEncoder;
    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final Notifier notifier;

    // ====================================================================== send money, wallet to wallet
    @Transactional
    public TransferResponse transfer(TransferRequest request, UUID senderUserId) {
        BigDecimal amount = validAmount(request.amount());

        CustomerEntity sender = customerRepository.findByUserId(senderUserId)
                .orElseThrow(() -> new BadRequestException("Customer account not found"));
        UserEntity senderUser = sender.getUser();

        UserEntity recipientUser = userRepository.findByPhone(normalize(request.phone()))
                .orElseThrow(() -> new BadRequestException(NOT_ON_WALLETPESA));
        if (recipientUser.getId().equals(senderUserId)) {
            throw new BadRequestException("You cannot send money to yourself.");
        }
        CustomerEntity recipient = customerRepository.findByUserId(recipientUser.getId())
                .orElseThrow(() -> new BadRequestException(NOT_ON_WALLETPESA));

        BigDecimal fee = tariffService.findApplicableTariff(TransactionType.TRANSFER.name(), amount).getFee();
        BigDecimal total = amount.add(fee);

        UUID senderWalletId = walletOf(senderUserId, WalletType.CUSTOMER_MAIN).getId();
        UUID recipientWalletId = walletOf(recipientUser.getId(), WalletType.CUSTOMER_MAIN).getId();
        UUID revenueWalletId = walletRepository.findByWalletType(WalletType.REVENUE)
                .orElseThrow(() -> new IllegalStateException("Revenue wallet not initialized")).getId();

        Map<UUID, WalletEntity> locked = lock(senderWalletId, recipientWalletId, revenueWalletId);
        WalletEntity senderWallet = locked.get(senderWalletId);
        WalletEntity recipientWallet = locked.get(recipientWalletId);
        WalletEntity revenueWallet = locked.get(revenueWalletId);

        // PIN, statuses, balance, limits: in that order.
        requirePin(request.pin(), senderUser, "Wrong PIN. Nothing was sent.");
        requireActive(sender, "Your account is not active.");
        if (senderWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new BadRequestException("Your wallet is not active.");
        }
        if (recipient.getStatus() != CustomerStatus.ACTIVE || recipientWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new BadRequestException("This wallet cannot receive money right now.");
        }
        if (senderWallet.getBalance().compareTo(total) < 0) {
            throw new BadRequestException("Not enough balance. You need TZS " + tzs(total) + " including the fee.");
        }
        limitService.checkSend(senderWalletId, sender.getKycStatus(), amount);
        limitService.checkReceive(recipientWallet.getBalance(), recipient.getKycStatus(), amount);

        senderWallet.setBalance(senderWallet.getBalance().subtract(total));
        recipientWallet.setBalance(recipientWallet.getBalance().add(amount));
        revenueWallet.setBalance(revenueWallet.getBalance().add(fee));

        List<JournalLineRequest> lines = new java.util.ArrayList<>(List.of(
                new JournalLineRequest(senderWalletId, EntryType.DEBIT, total),
                new JournalLineRequest(recipientWalletId, EntryType.CREDIT, amount)));
        if (fee.signum() > 0) {
            lines.add(new JournalLineRequest(revenueWalletId, EntryType.CREDIT, fee));
        }
        ledgerService.postJournal(LedgerTransactionType.TRANSFER, "Wallet transfer to " + recipient.getPhoneNumber(), senderUser, lines);

        walletRepository.save(senderWallet);
        walletRepository.save(recipientWallet);
        walletRepository.save(revenueWallet);

        String referenceNo = referenceNo("TRF");
        transactionRepository.save(TransactionEntity.builder()
                .referenceNo(referenceNo).senderWallet(senderWallet).receiverWallet(recipientWallet)
                .amount(amount).fee(fee).commission(BigDecimal.ZERO)
                .transactionType(TransactionType.TRANSFER.name()).status(TransactionStatus.COMPLETED.name()).build());

        beneficiaryService.remember(senderUser, recipientWallet);

        String senderName = fullName(sender);
        notifier.notify(NotificationKey.C3, NotificationRecipient.of(senderUser), Map.of(
                "amount", tzs(amount), "name", fullName(recipient), "phone", NotificationFormat.maskedPhone(recipientUser.getPhone()),
                "ref", referenceNo, "balance", tzs(senderWallet.getBalance())));
        notifier.notify(NotificationKey.C4, NotificationRecipient.of(recipientUser), Map.of(
                "amount", tzs(amount), "name", senderName, "phone", NotificationFormat.maskedPhone(senderUser.getPhone()),
                "ref", referenceNo, "balance", tzs(recipientWallet.getBalance())));

        return new TransferResponse(referenceNo, TransactionStatus.COMPLETED.name(), senderWallet.getCurrency(), amount, fee, total,
                com.application.e_wallet.wallet.service.WalletLookupService.maskedName(recipient), NotificationFormat.maskedPhone(recipientUser.getPhone()),
                senderWallet.getBalance(), OffsetDateTime.now());
    }

    // ====================================================================== withdraw cash at an agent
    @Transactional
    public WithdrawResponse withdraw(WithdrawRequest request, UUID customerUserId) {
        BigDecimal amount = validAmount(request.amount());

        CustomerEntity customer = customerRepository.findByUserId(customerUserId)
                .orElseThrow(() -> new BadRequestException("Customer account not found"));
        UserEntity customerUser = customer.getUser();

        String till = request.tillNumber().replaceAll("\\s", "");
        AgentEntity agent = agentRepository.findByTillNumber(till)
                .filter(a -> a.getAgentStatus() == AgentStatus.ACTIVE)
                .orElseThrow(() -> new BadRequestException("No active agent has this till number."));
        UserEntity agentUser = agent.getUser();

        TariffEntity tariff = tariffService.findApplicableTariff(TransactionType.CASH_OUT.name(), amount);
        BigDecimal fee = tariff.getFee();
        BigDecimal commission = tariff.getAgentCommission();
        BigDecimal total = amount.add(fee);

        UUID customerWalletId = walletOf(customerUserId, WalletType.CUSTOMER_MAIN).getId();
        UUID agentFloatId = walletOf(agentUser.getId(), WalletType.AGENT_FLOAT).getId();
        UUID agentCommissionId = walletOf(agentUser.getId(), WalletType.AGENT_COMMISSION).getId();
        UUID poolId = walletRepository.findByWalletType(WalletType.COMMISSION)
                .orElseThrow(() -> new IllegalStateException("Commission wallet not initialized")).getId();
        UUID revenueId = walletRepository.findByWalletType(WalletType.REVENUE)
                .orElseThrow(() -> new IllegalStateException("Revenue wallet not initialized")).getId();

        Map<UUID, WalletEntity> locked = lock(customerWalletId, agentFloatId, agentCommissionId, poolId, revenueId);
        WalletEntity customerWallet = locked.get(customerWalletId);
        WalletEntity agentFloat = locked.get(agentFloatId);
        WalletEntity agentCommission = locked.get(agentCommissionId);
        WalletEntity pool = locked.get(poolId);
        WalletEntity revenue = locked.get(revenueId);

        requirePin(request.pin(), customerUser, "Wrong PIN. Nothing was withdrawn.");
        requireActive(customer, "Your account is not active.");
        if (customerWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new BadRequestException("Your wallet is not active.");
        }
        if (agentFloat.getStatus() != WalletStatus.ACTIVE) {
            throw new BadRequestException("This agent cannot serve withdrawals right now.");
        }
        if (customerWallet.getBalance().compareTo(total) < 0) {
            throw new BadRequestException("Not enough balance. You need TZS " + tzs(total) + " including the fee.");
        }
        limitService.checkSend(customerWalletId, customer.getKycStatus(), amount);

        // The agent's commission comes from the pool. An empty pool never blocks a customer: the commission is
        // recorded as owed and paid after the next refill.
        boolean commissionPaidNow = commissionService.poolCanPay(pool, commission);

        customerWallet.setBalance(customerWallet.getBalance().subtract(total));
        agentFloat.setBalance(agentFloat.getBalance().add(amount));
        revenue.setBalance(revenue.getBalance().add(fee));

        List<JournalLineRequest> lines = new java.util.ArrayList<>();
        lines.add(new JournalLineRequest(customerWalletId, EntryType.DEBIT, total));
        lines.add(new JournalLineRequest(agentFloatId, EntryType.CREDIT, amount));
        if (fee.signum() > 0) {
            lines.add(new JournalLineRequest(revenueId, EntryType.CREDIT, fee));
        }
        if (commissionPaidNow && commission.signum() > 0) {
            pool.setBalance(pool.getBalance().subtract(commission));
            agentCommission.setBalance(agentCommission.getBalance().add(commission));
            lines.add(new JournalLineRequest(poolId, EntryType.DEBIT, commission));
            lines.add(new JournalLineRequest(agentCommissionId, EntryType.CREDIT, commission));
        }
        ledgerService.postJournal(LedgerTransactionType.CASH_OUT, "Withdrawal at agent " + agent.getAgentNumber(), customerUser, lines);

        walletRepository.save(customerWallet);
        walletRepository.save(agentFloat);
        walletRepository.save(agentCommission);
        walletRepository.save(pool);
        walletRepository.save(revenue);

        String referenceNo = referenceNo("WDR");
        String matchCode = String.format("%04d", RANDOM.nextInt(10_000));
        TransactionEntity transaction = transactionRepository.save(TransactionEntity.builder()
                .referenceNo(referenceNo).senderWallet(customerWallet).receiverWallet(agentFloat)
                .amount(amount).fee(fee).commission(commission).matchCode(matchCode)
                .transactionType(TransactionType.CASH_OUT.name()).status(TransactionStatus.COMPLETED.name()).build());
        if (!commissionPaidNow) {
            commissionService.recordOwed(agent, transaction, commission);
        }

        notifier.notify(NotificationKey.C5, NotificationRecipient.of(customerUser), Map.of(
                "amount", tzs(amount), "agent", agent.getBusinessName(), "till", NotificationFormat.till(agent.getTillNumber()),
                "fee", tzs(fee), "code", matchCode, "ref", referenceNo, "balance", tzs(customerWallet.getBalance())));
        notifier.notify(NotificationKey.A2, NotificationRecipient.of(agentUser), Map.of(
                "amount", tzs(amount), "name", fullName(customer), "code", matchCode,
                "commission", tzs(commission), "float", tzs(agentFloat.getBalance())));

        return new WithdrawResponse(referenceNo, TransactionStatus.COMPLETED.name(), customerWallet.getCurrency(), amount, fee, total, matchCode,
                new WithdrawResponse.AgentInfo(agent.getBusinessName(), agent.getTillNumber(), agent.getDistrict().getName(), agent.getRegion().getName()),
                customerWallet.getBalance(), OffsetDateTime.now());
    }

    // ====================================================================== helpers
    private BigDecimal validAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BadRequestException("Amount must be greater than zero.");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new BadRequestException("Amount must have at most 2 decimal places.");
        }
        return amount;
    }

    private String normalize(String phone) {
        try {
            return phoneNumberNormalizer.normalize(phone);
        } catch (RuntimeException invalid) {
            throw new BadRequestException(NOT_ON_WALLETPESA);
        }
    }

    private WalletEntity walletOf(UUID userId, WalletType type) {
        return walletRepository.findByUserIdAndWalletType(userId, type)
                .orElseThrow(() -> new BadRequestException("Wallet not found"));
    }

    /** All wallets in one ordered statement, so two postings over the same wallets can never deadlock. */
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

    private void requireActive(CustomerEntity customer, String message) {
        if (customer.getStatus() != CustomerStatus.ACTIVE) {
            throw new BadRequestException(message);
        }
    }

    private static String fullName(CustomerEntity customer) {
        return customer.getFirstName().trim() + " " + customer.getLastName().trim();
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
