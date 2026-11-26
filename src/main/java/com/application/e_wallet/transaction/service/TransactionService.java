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
import com.application.e_wallet.tariff.entity.TariffEntity;
import com.application.e_wallet.tariff.service.TariffService;
import com.application.e_wallet.transaction.dto.CashInRequest;
import com.application.e_wallet.transaction.dto.CashInResponse;
import com.application.e_wallet.transaction.dto.TransactionDetailResponse;
import com.application.e_wallet.transaction.dto.TransactionListItem;
import com.application.e_wallet.transaction.dto.TransactionListResponse;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.application.e_wallet.notification.NotificationFormat;
import com.application.e_wallet.notification.NotificationKey;
import com.application.e_wallet.notification.NotificationRecipient;
import com.application.e_wallet.transaction.dto.MyTransactionItem;
import com.application.e_wallet.transaction.dto.MyTransactionList;
import java.util.ArrayList;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private static final DateTimeFormatter REFERENCE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final AgentRepository agentRepository;
    private final CustomerRepository customerRepository;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final TariffService tariffService;
    private final LedgerService ledgerService;
    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final PasswordEncoder pinEncoder;
    private final com.application.e_wallet.limits.LimitService limitService;
    private final CommissionService commissionService;
    private final com.application.e_wallet.notification.Notifier notifier;

    //=================== AGENT CASH-IN: CUSTOMER GIVES CASH TO AGENT FOR THE WALLET ADDITION ==============
    @Transactional
    public CashInResponse cashIn(CashInRequest request, UUID userId) {

        AgentEntity agent = agentRepository.findByUserId(userId)
                .orElseThrow(() -> new BadRequestException("Agent account not found"));
        if (agent.getAgentStatus() != AgentStatus.ACTIVE) {
            throw new BadRequestException("Agent is not active");
        }
        UserEntity agentUser = agent.getUser();

        WalletEntity agentFloatWallet = walletRepository.findByUserIdAndWalletTypeForUpdate(agentUser.getId(), WalletType.AGENT_FLOAT)
                .orElseThrow(() -> new BadRequestException("Agent float wallet not found"));
        if (!agentFloatWallet.getAccountNumber().equals(request.agentAccountNumber())) {
            throw new BadRequestException("Agent account number does not match the authenticated agent");
        }
        if (agentFloatWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new BadRequestException("Agent float wallet is not active");
        }
        if (!pinEncoder.matches(request.agentPin(), agentUser.getPinHash())) {
            throw new BadRequestException("Invalid agent PIN");
        }

        WalletEntity customerWallet = walletRepository.findByAccountNumberForUpdate(request.accountNumber())
                .orElseThrow(() -> new BadRequestException("Customer account not found"));
        if (customerWallet.getWalletType() != WalletType.CUSTOMER_MAIN) {
            throw new BadRequestException("Account number does not belong to a customer wallet");
        }

        UserEntity customerUser = customerWallet.getUser();
        CustomerEntity customer = customerRepository.findByUserId(customerUser.getId())
                .orElseThrow(() -> new BadRequestException("Customer not found"));
        if (customer.getStatus() != CustomerStatus.ACTIVE) {
            throw new BadRequestException("Customer account is not active");
        }

        // The account number and the phone must belong to the same person.
        String normalizedPhone = phoneNumberNormalizer.normalize(request.phone());
        if (!customer.getPhoneNumber().equals(normalizedPhone)) {
            throw new BadRequestException("Phone number does not match the account holder");
        }

        BigDecimal amount = request.amount();
        TariffEntity tariff = tariffService.findApplicableTariff(TransactionType.CASH_IN.name(), amount);
        BigDecimal fee = tariff.getFee();
        BigDecimal commission = tariff.getAgentCommission();

        WalletEntity commissionPoolWallet = walletRepository.findByWalletTypeForUpdate(WalletType.COMMISSION)
                .orElseThrow(() -> new IllegalStateException("Commission wallet not initialized"));
        WalletEntity agentCommissionWallet = walletRepository.findByUserIdAndWalletTypeForUpdate(agentUser.getId(), WalletType.AGENT_COMMISSION)
                .orElseThrow(() -> new BadRequestException("Agent commission wallet not found"));

        if (customerWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new BadRequestException("Customer wallet is not active");
        }
        if (agentFloatWallet.getBalance().compareTo(amount) < 0) {
            throw new BadRequestException("Insufficient agent float balance");
        }
        limitService.checkReceive(customerWallet.getBalance(), customer.getKycStatus(), amount);

        // An empty commission pool never blocks the cash-in: the commission is owed and paid after the next refill.
        boolean commissionPaidNow = commissionService.poolCanPay(commissionPoolWallet, commission);

        agentFloatWallet.setBalance(agentFloatWallet.getBalance().subtract(amount));
        customerWallet.setBalance(customerWallet.getBalance().add(amount));

        List<JournalLineRequest> lines = new ArrayList<>();
        lines.add(new JournalLineRequest(agentFloatWallet.getId(), EntryType.DEBIT, amount));
        lines.add(new JournalLineRequest(customerWallet.getId(), EntryType.CREDIT, amount));
        if (commissionPaidNow && commission.signum() > 0) {
            commissionPoolWallet.setBalance(commissionPoolWallet.getBalance().subtract(commission));
            agentCommissionWallet.setBalance(agentCommissionWallet.getBalance().add(commission));
            lines.add(new JournalLineRequest(commissionPoolWallet.getId(), EntryType.DEBIT, commission));
            lines.add(new JournalLineRequest(agentCommissionWallet.getId(), EntryType.CREDIT, commission));
        }
        ledgerService.postJournal(LedgerTransactionType.CASH_IN,
                "Agent cash-in for " + customer.getPhoneNumber(), agentUser, lines);

        walletRepository.save(agentFloatWallet);
        walletRepository.save(customerWallet);
        walletRepository.save(commissionPoolWallet);
        walletRepository.save(agentCommissionWallet);

        String referenceNo = generateReferenceNo();

        TransactionEntity transaction = transactionRepository.save(TransactionEntity.builder()
                .referenceNo(referenceNo)
                .senderWallet(agentFloatWallet)
                .receiverWallet(customerWallet)
                .amount(amount)
                .fee(fee)
                .commission(commission)
                .transactionType(TransactionType.CASH_IN.name())
                .status(TransactionStatus.COMPLETED.name())
                .build());
        if (!commissionPaidNow) {
            commissionService.recordOwed(agent, transaction, commission);
        }

        notifier.notify(NotificationKey.C2, NotificationRecipient.of(customerUser), java.util.Map.of(
                "amount", NotificationFormat.tzs(amount), "agent", agent.getBusinessName(), "agentId", agent.getAgentNumber(),
                "ref", referenceNo, "balance", NotificationFormat.tzs(customerWallet.getBalance())));
        notifier.notify(NotificationKey.A1, NotificationRecipient.of(agentUser), java.util.Map.of(
                "amount", NotificationFormat.tzs(amount), "name", customer.getFirstName().trim() + " " + customer.getLastName().trim(),
                "ref", referenceNo, "commission", NotificationFormat.tzs(commission), "float", NotificationFormat.tzs(agentFloatWallet.getBalance())));

        return new CashInResponse(
                referenceNo,
                TransactionStatus.COMPLETED.name(),
                customerWallet.getCurrency(),
                amount,
                fee,
                commission,
                new CashInResponse.CustomerLeg(customer.getPhoneNumber(), customerWallet.getBalance()),
                new CashInResponse.AgentLeg(agentFloatWallet.getAccountNumber(), agentFloatWallet.getBalance(), agentCommissionWallet.getBalance()),
                OffsetDateTime.now()
        );
    }

    //=================== MY TRANSACTIONS: filter by type and date, search, page ==============
    @Transactional(readOnly = true)
    public MyTransactionList listMine(UUID callerUserId, String type, java.time.LocalDate from, java.time.LocalDate to, String q, Pageable pageable) {
        List<UUID> ownWalletIds = resolveOwnWalletIds(callerUserId);
        java.time.ZoneId zone = java.time.ZoneId.of("Africa/Dar_es_Salaam");
        OffsetDateTime start = (from == null ? java.time.LocalDate.of(2000, 1, 1) : from).atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime end = (to == null ? java.time.LocalDate.of(2100, 1, 1) : to.plusDays(1)).atStartOfDay(zone).toOffsetDateTime();
        String search = q == null ? "" : q.trim().toLowerCase();

        Page<TransactionEntity> page = transactionRepository.findMine(ownWalletIds, type == null ? "" : type.trim().toUpperCase(),
                start, end, search, pageable);
        List<MyTransactionItem> items = page.getContent().stream().map(t -> toMyItem(t, ownWalletIds)).toList();
        return new MyTransactionList(items, page.getNumber(), page.getSize(), page.getTotalElements());
    }

    private MyTransactionItem toMyItem(TransactionEntity t, List<UUID> own) {
        boolean incoming = own.contains(t.getReceiverWallet().getId()) && !own.contains(t.getSenderWallet().getId());
        WalletEntity other = incoming ? t.getSenderWallet() : t.getReceiverWallet();
        String name = other.getAccountName() == null ? "" : other.getAccountName();
        int suffix = name.indexOf(" — ");
        return new MyTransactionItem(t.getReferenceNo(), t.getTransactionType(), incoming ? "IN" : "OUT", t.getAmount(), t.getFee(),
                t.getStatus(), t.getCreatedAt(), suffix == -1 ? name : name.substring(0, suffix),
                NotificationFormat.maskedPhone(other.getUser().getPhone()), incoming && t.getMatchCode() != null ? null : t.getMatchCode());
    }

    //=================== VIEW TRANSACTIONS: "all" (admin) or "own" (customer/agent) ==============
    @Transactional(readOnly = true)
    public TransactionListResponse listTransactions(UUID callerUserId, boolean canViewAll, Pageable pageable) {
        Page<TransactionEntity> page = canViewAll
                ? transactionRepository.findAllWithWallets(pageable)
                : transactionRepository.findByWalletIdInWithWallets(resolveOwnWalletIds(callerUserId), pageable);

        List<TransactionListItem> items = page.getContent().stream()
                .map(this::toListItem)
                .toList();

        return new TransactionListResponse(items, page.getNumber(), page.getSize(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public TransactionDetailResponse getTransactionDetail(UUID callerUserId, boolean canViewAll, String referenceNo) {
        TransactionEntity transaction = transactionRepository.findByReferenceNo(referenceNo)
                .orElseThrow(() -> new BadRequestException("Transaction not found"));

        if (!canViewAll) {
            List<UUID> ownWalletIds = resolveOwnWalletIds(callerUserId);
            boolean isOwn = ownWalletIds.contains(transaction.getSenderWallet().getId())
                    || ownWalletIds.contains(transaction.getReceiverWallet().getId());
            // Same "not found" message whether the reference genuinely doesn't exist or it
            // belongs to someone else - a distinct "forbidden" response would confirm to the
            // caller that a reference they don't own actually exists.
            if (!isOwn) {
                throw new BadRequestException("Transaction not found");
            }
        }

        return toDetailResponse(transaction, canViewAll ? null : resolveOwnWalletIds(callerUserId), canViewAll);
    }

    // A caller is either a customer (one wallet: CUSTOMER_MAIN) or an agent (two wallets: float +
    // commission) - never both. Tried in that order since customers vastly outnumber agents.
    private List<UUID> resolveOwnWalletIds(UUID userId) {
        if (customerRepository.findByUserId(userId).isPresent()) {
            WalletEntity wallet = walletRepository.findByUserIdAndWalletType(userId, WalletType.CUSTOMER_MAIN)
                    .orElseThrow(() -> new IllegalStateException("Customer wallet not initialized"));
            return List.of(wallet.getId());
        }

        if (agentRepository.findByUserId(userId).isPresent()) {
            WalletEntity floatWallet = walletRepository.findByUserIdAndWalletType(userId, WalletType.AGENT_FLOAT)
                    .orElseThrow(() -> new IllegalStateException("Agent float wallet not found"));
            WalletEntity commissionWallet = walletRepository.findByUserIdAndWalletType(userId, WalletType.AGENT_COMMISSION)
                    .orElseThrow(() -> new IllegalStateException("Agent commission wallet not found"));
            return List.of(floatWallet.getId(), commissionWallet.getId());
        }

        throw new BadRequestException("No customer or agent profile for this account");
    }

    private TransactionListItem toListItem(TransactionEntity transaction) {
        return new TransactionListItem(
                transaction.getReferenceNo(),
                transaction.getTransactionType(),
                transaction.getAmount(),
                transaction.getFee(),
                transaction.getCommission(),
                transaction.getStatus(),
                transaction.getCreatedAt(),
                transaction.getSenderWallet().getAccountNumber(),
                transaction.getSenderWallet().getAccountName(),
                transaction.getReceiverWallet().getAccountNumber(),
                transaction.getReceiverWallet().getAccountName()
        );
    }

    /** Staff see full account numbers. Everyone else sees the last 4 only, plus who was on the other side. */
    private TransactionDetailResponse toDetailResponse(TransactionEntity transaction, List<UUID> ownWalletIds, boolean staff) {
        WalletEntity sender = transaction.getSenderWallet();
        WalletEntity receiver = transaction.getReceiverWallet();
        boolean incoming = ownWalletIds != null && ownWalletIds.contains(receiver.getId()) && !ownWalletIds.contains(sender.getId());
        WalletEntity other = incoming ? sender : receiver;
        String otherName = other.getAccountName() == null ? "" : other.getAccountName();
        int suffix = otherName.indexOf(" — ");

        return new TransactionDetailResponse(
                transaction.getReferenceNo(),
                transaction.getTransactionType(),
                transaction.getAmount(),
                transaction.getFee(),
                transaction.getCommission(),
                transaction.getStatus(),
                transaction.getCreatedAt(),
                staff ? sender.getAccountNumber() : lastFour(sender.getAccountNumber()),
                sender.getAccountName(),
                staff ? receiver.getAccountNumber() : lastFour(receiver.getAccountNumber()),
                receiver.getAccountName(),
                transaction.getExternalReference(),
                ownWalletIds == null ? null : (incoming ? "IN" : "OUT"),
                ownWalletIds == null ? null : (suffix == -1 ? otherName : otherName.substring(0, suffix)),
                ownWalletIds == null ? null : other.getUser() == null ? "" : com.application.e_wallet.notification.NotificationFormat.maskedPhone(other.getUser().getPhone()),
                incoming ? null : transaction.getMatchCode()
        );
    }

    private static String lastFour(String accountNumber) {
        return accountNumber == null || accountNumber.length() < 4 ? "" : "••" + accountNumber.substring(accountNumber.length() - 4);
    }

    private String generateReferenceNo() {
        String referenceNo;
        do {
            String datePart = OffsetDateTime.now().format(REFERENCE_DATE_FORMAT);
            String randomPart = UUID.randomUUID()
                    .toString()
                    .replace("-", "")
                    .substring(0, 8)
                    .toUpperCase();
            referenceNo = "TXN-" + datePart + "-" + randomPart;
        } while (transactionRepository.existsByReferenceNo(referenceNo));

        return referenceNo;
    }
}
