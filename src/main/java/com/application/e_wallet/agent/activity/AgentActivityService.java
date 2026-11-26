package com.application.e_wallet.agent.activity;

import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.notification.NotificationFormat;
import com.application.e_wallet.transaction.entity.TransactionEntity;
import com.application.e_wallet.transaction.repository.TransactionRepository;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What an agent has done at the till: the cash-ins they made and the customer withdrawals they paid, as a searchable
 * history, a live feed of the latest withdrawals, and a 7-day chart. Only rows where this agent's float wallet is a
 * party are ever returned, and another agent's reference answers "not found", so references cannot be probed.
 */
@Service
@RequiredArgsConstructor
public class AgentActivityService {

    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    static final Set<String> TILL_TYPES = Set.of("CASH_IN", "CASH_OUT");

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    @Transactional(readOnly = true)
    public AgentTransactionPage list(UUID agentUserId, String type, LocalDate from, LocalDate to, String q, int page, int size) {
        UUID floatId = floatOf(agentUserId).getId();
        Set<String> types = type != null && TILL_TYPES.contains(type.trim().toUpperCase()) ? Set.of(type.trim().toUpperCase()) : TILL_TYPES;
        OffsetDateTime start = (from == null ? LocalDate.of(2000, 1, 1) : from).atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime end = (to == null ? LocalDate.of(2100, 1, 1) : to.plusDays(1)).atStartOfDay(ZONE).toOffsetDateTime();
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50));

        Page<TransactionEntity> result = transactionRepository.findAgentActivity(floatId, types, start, end, q == null ? "" : q.trim().toLowerCase(), pageable);
        List<AgentTransactionRow> items = result.getContent().stream().map(t -> toRow(t, floatId, false)).toList();
        return new AgentTransactionPage(items, result.getNumber(), result.getSize(), result.getTotalElements(), todayKpis(floatId));
    }

    @Transactional(readOnly = true)
    public AgentTransactionRow detail(UUID agentUserId, String reference) {
        UUID floatId = floatOf(agentUserId).getId();
        TransactionEntity t = transactionRepository.findByReferenceNo(reference)
                .filter(tx -> TILL_TYPES.contains(tx.getTransactionType()))
                .filter(tx -> tx.getSenderWallet().getId().equals(floatId) || tx.getReceiverWallet().getId().equals(floatId))
                .orElseThrow(() -> new BadRequestException("Transaction not found"));
        return toRow(t, floatId, true);
    }

    /** Withdrawals paid at the till in the last few minutes, newest first. The window is 1 minute to 24 hours. */
    @Transactional(readOnly = true)
    public List<LiveWithdrawal> live(UUID agentUserId, int minutes) {
        UUID floatId = floatOf(agentUserId).getId();
        OffsetDateTime since = OffsetDateTime.now().minusMinutes(Math.min(Math.max(minutes, 1), 24 * 60));
        return transactionRepository.findLiveWithdrawals(floatId, since, PageRequest.of(0, 50)).stream().map(t -> {
            WalletEntity customer = t.getSenderWallet();
            return new LiveWithdrawal(t.getReferenceNo(), t.getAmount(), nameOf(customer), maskedPhoneOf(customer), t.getMatchCode(), t.getCommission(), t.getCreatedAt());
        }).toList();
    }

    /** The last 7 days (today included), oldest first, with a row for every day even when nothing happened. */
    @Transactional(readOnly = true)
    public List<WeeklyDay> weekly(UUID agentUserId) {
        UUID floatId = floatOf(agentUserId).getId();
        LocalDate today = LocalDate.now(ZONE);
        LocalDate first = today.minusDays(6);
        List<TransactionEntity> rows = transactionRepository.findAgentActivityBetween(floatId, TILL_TYPES,
                first.atStartOfDay(ZONE).toOffsetDateTime(), today.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime());
        List<WeeklyDay> days = new ArrayList<>();
        for (LocalDate day = first; !day.isAfter(today); day = day.plusDays(1)) {
            long cashIns = 0;
            long withdrawals = 0;
            BigDecimal cashInAmount = BigDecimal.ZERO;
            BigDecimal withdrawalAmount = BigDecimal.ZERO;
            for (TransactionEntity t : rows) {
                if (!t.getCreatedAt().atZoneSameInstant(ZONE).toLocalDate().equals(day) || !"COMPLETED".equals(t.getStatus())) {
                    continue;
                }
                if ("CASH_IN".equals(t.getTransactionType())) {
                    cashIns++;
                    cashInAmount = cashInAmount.add(t.getAmount());
                } else {
                    withdrawals++;
                    withdrawalAmount = withdrawalAmount.add(t.getAmount());
                }
            }
            days.add(new WeeklyDay(day, cashIns, cashInAmount, withdrawals, withdrawalAmount));
        }
        return days;
    }

    private AgentTransactionPage.Kpis todayKpis(UUID floatId) {
        LocalDate today = LocalDate.now(ZONE);
        List<TransactionEntity> rows = transactionRepository.findAgentActivityBetween(floatId, TILL_TYPES,
                today.atStartOfDay(ZONE).toOffsetDateTime(), today.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime());
        long cashIns = rows.stream().filter(t -> "CASH_IN".equals(t.getTransactionType())).count();
        long pending = rows.stream().filter(t -> "PENDING".equals(t.getStatus())).count();
        BigDecimal commission = rows.stream().filter(t -> "COMPLETED".equals(t.getStatus())).map(TransactionEntity::getCommission).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new AgentTransactionPage.Kpis(rows.size(), cashIns, rows.size() - cashIns, pending, commission);
    }

    private AgentTransactionRow toRow(TransactionEntity t, UUID floatId, boolean fullPhone) {
        boolean cashIn = t.getSenderWallet().getId().equals(floatId);
        WalletEntity customer = cashIn ? t.getReceiverWallet() : t.getSenderWallet();
        return new AgentTransactionRow(t.getReferenceNo(), t.getTransactionType(), nameOf(customer),
                fullPhone ? phoneOf(customer) : maskedPhoneOf(customer), customer.getAccountNumber(),
                t.getAmount(), t.getFee(), t.getCommission(), t.getStatus(), t.getCreatedAt());
    }

    private static String nameOf(WalletEntity wallet) {
        String name = wallet.getAccountName() == null ? "" : wallet.getAccountName();
        int suffix = name.indexOf(" — ");
        return suffix == -1 ? name : name.substring(0, suffix);
    }

    private static String phoneOf(WalletEntity wallet) {
        return wallet.getUser() == null ? "" : wallet.getUser().getPhone();
    }

    private static String maskedPhoneOf(WalletEntity wallet) {
        return wallet.getUser() == null ? "" : NotificationFormat.maskedPhone(wallet.getUser().getPhone());
    }

    private WalletEntity floatOf(UUID agentUserId) {
        return walletRepository.findByUserIdAndWalletType(agentUserId, WalletType.AGENT_FLOAT)
                .orElseThrow(() -> new BadRequestException("No agent profile for this account"));
    }
}
