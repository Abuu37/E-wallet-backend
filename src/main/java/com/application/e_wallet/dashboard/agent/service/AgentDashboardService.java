package com.application.e_wallet.dashboard.agent.service;

import com.application.e_wallet.agent.dto.FloatHealth;
import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.dashboard.DashboardRange;
import com.application.e_wallet.dashboard.DashboardRangeSupport;
import com.application.e_wallet.dashboard.DashboardRangeSupport.DayWindow;
import com.application.e_wallet.dashboard.DashboardRangeSupport.TransactionPeriod;
import com.application.e_wallet.dashboard.TransactionTypeMetric;
import com.application.e_wallet.dashboard.TrendPoint;
import com.application.e_wallet.dashboard.agent.dto.AgentCommissionAccount;
import com.application.e_wallet.dashboard.agent.dto.AgentDashboardOverviewResponse;
import com.application.e_wallet.dashboard.agent.dto.AgentDashboardProfile;
import com.application.e_wallet.dashboard.agent.dto.AgentDashboardWallets;
import com.application.e_wallet.dashboard.agent.dto.AgentFloatAccount;
import com.application.e_wallet.dashboard.agent.dto.AgentFloatRequestStatus;
import com.application.e_wallet.dashboard.agent.dto.AgentLatestFloatRequest;
import com.application.e_wallet.dashboard.agent.dto.AgentRecentTransaction;
import com.application.e_wallet.dashboard.agent.dto.AgentSummary;
import com.application.e_wallet.dashboard.agent.dto.AgentTodaySummary;
import com.application.e_wallet.dashboard.agent.dto.AgentTransactionVolume;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.transaction.entity.TransactionEntity;
import com.application.e_wallet.transaction.entity.TransactionStatus;
import com.application.e_wallet.transaction.entity.TransactionType;
import com.application.e_wallet.transaction.repository.TransactionRepository;
import com.application.e_wallet.treasury.entity.RequestStatus;
import com.application.e_wallet.treasury.entity.TreasuryRequestEntity;
import com.application.e_wallet.treasury.repository.TreasuryRequestRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AgentDashboardService {

    private static final int RECENT_TRANSACTIONS_LIMIT = 5;

    static final ZoneId DASHBOARD_ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private final AgentRepository agentRepository;
    private final WalletRepository walletRepository;
    private final TreasuryRequestRepository treasuryRequestRepository;
    private final TransactionRepository transactionRepository;
    private final CustomerRepository customerRepository;

    // Convenience overload - defaults to range=today, matching this endpoint's original
    // (pre-range) behavior, so every existing caller/test keeps working unchanged.
    @Transactional(readOnly = true)
    public AgentDashboardOverviewResponse getOverview(UUID callerUserId) {
        return getOverview(callerUserId, "today", null, null);
    }

    @Transactional(readOnly = true)
    public AgentDashboardOverviewResponse getOverview(UUID callerUserId, String rawRange, LocalDate from, LocalDate to) {
        AgentEntity agent = agentRepository.findByUserId(callerUserId)
                .orElseThrow(() -> new BadRequestException("No agent profile for this account"));

        WalletEntity floatWallet = walletRepository.findByUserIdAndWalletType(callerUserId, WalletType.AGENT_FLOAT)
                .orElseThrow(() -> new IllegalStateException("Agent float wallet not found"));
        WalletEntity commissionWallet = walletRepository.findByUserIdAndWalletType(callerUserId, WalletType.AGENT_COMMISSION)
                .orElseThrow(() -> new IllegalStateException("Agent commission wallet not found"));

        List<UUID> walletIds = List.of(floatWallet.getId(), commissionWallet.getId());
        OffsetDateTime now = OffsetDateTime.now(DASHBOARD_ZONE);
        OffsetDateTime startOfDay = now.toLocalDate().atStartOfDay(DASHBOARD_ZONE).toOffsetDateTime();

        List<TransactionEntity> todayTransactions = transactionRepository.findByStatusAndCreatedAtBetweenAndWalletIdIn(
                TransactionStatus.COMPLETED.name(), startOfDay, now, walletIds);

        DashboardRange range = DashboardRangeSupport.parseRange(rawRange);
        TransactionPeriod period = DashboardRangeSupport.resolvePeriod(range, from, to);

        return new AgentDashboardOverviewResponse(
                now,
                floatWallet.getCurrency(),
                buildSummary(floatWallet, commissionWallet, walletIds),
                buildProfile(agent),
                buildWallets(floatWallet, commissionWallet),
                buildFloatRequestStatus(agent.getId()),
                buildTodaySummary(todayTransactions),
                buildRecentTransactions(walletIds),
                buildTransactionVolume(walletIds, range, period)
        );
    }

    // Package-private so tests can call this section builder directly without going through
    // the public getOverview() entry point. All-time (unscoped by date/range) at-a-glance card:
    // current balances plus the lifetime count of transactions touching either wallet.
    AgentSummary buildSummary(WalletEntity floatWallet, WalletEntity commissionWallet, List<UUID> walletIds) {
        long totalTransactions = transactionRepository.countByWalletIdIn(walletIds);

        return new AgentSummary(
                floatWallet.getBalance(),
                commissionWallet.getBalance(),
                totalTransactions,
                FloatHealth.fromBalance(floatWallet.getBalance())
        );
    }

    // Package-private so tests can call this section builder directly without going through
    // the public getOverview() entry point - the same "today" summary above stays unaffected by
    // range (it's the at-a-glance commission/cash-in/cash-out numbers); this is the separate,
    // range-customizable graph the admin dashboard already has (today/week/custom).
    AgentTransactionVolume buildTransactionVolume(List<UUID> walletIds, DashboardRange range, TransactionPeriod period) {
        DayWindow window = period.window();
        List<TransactionEntity> transactions = transactionRepository.findByStatusAndCreatedAtBetweenAndWalletIdIn(
                TransactionStatus.COMPLETED.name(), window.start(), window.end(), walletIds);

        BigDecimal totalVolume = BigDecimal.ZERO;
        long totalCount = 0;
        BigDecimal cashInVolume = BigDecimal.ZERO;
        long cashInCount = 0;
        BigDecimal cashOutVolume = BigDecimal.ZERO;
        long cashOutCount = 0;

        for (TransactionEntity transaction : transactions) {
            totalVolume = totalVolume.add(transaction.getAmount());
            totalCount++;

            if (TransactionType.CASH_IN.name().equals(transaction.getTransactionType())) {
                cashInVolume = cashInVolume.add(transaction.getAmount());
                cashInCount++;
            } else if (TransactionType.CASH_OUT.name().equals(transaction.getTransactionType())) {
                cashOutVolume = cashOutVolume.add(transaction.getAmount());
                cashOutCount++;
            }
        }

        List<TrendPoint> trend = DashboardRangeSupport.buildTrend(range, transactions, period);

        return new AgentTransactionVolume(
                range.name(),
                period.periodStart(),
                period.periodEnd(),
                normalizeScale(totalVolume),
                totalCount,
                new TransactionTypeMetric(cashInCount, normalizeScale(cashInVolume)),
                new TransactionTypeMetric(cashOutCount, normalizeScale(cashOutVolume)),
                trend
        );
    }

    private AgentDashboardProfile buildProfile(AgentEntity agent) {
        return new AgentDashboardProfile(
                agent.getAgentNumber(),
                agent.getBusinessName(),
                agent.getAgentStatus().name()
        );
    }

    private AgentDashboardWallets buildWallets(WalletEntity floatWallet, WalletEntity commissionWallet) {
        AgentFloatAccount floatAccount = new AgentFloatAccount(
                floatWallet.getAccountNumber(), floatWallet.getBalance(), FloatHealth.fromBalance(floatWallet.getBalance()));
        AgentCommissionAccount commissionAccount = new AgentCommissionAccount(
                commissionWallet.getAccountNumber(), commissionWallet.getBalance());

        return new AgentDashboardWallets(floatAccount, commissionAccount);
    }

    private AgentFloatRequestStatus buildFloatRequestStatus(UUID agentId) {
        long pendingCount = treasuryRequestRepository.countByAgent_IdAndStatus(agentId, RequestStatus.PENDING);

        List<TreasuryRequestEntity> latest = treasuryRequestRepository
                .findAllByAgent_IdOrderByCreatedAtDesc(agentId, PageRequest.of(0, 1));

        AgentLatestFloatRequest latestRequest = latest.isEmpty() ? null : toLatestFloatRequest(latest.get(0));

        return new AgentFloatRequestStatus(pendingCount, latestRequest);
    }

    private AgentLatestFloatRequest toLatestFloatRequest(TreasuryRequestEntity request) {
        return new AgentLatestFloatRequest(
                request.getId(),
                request.getAmount(),
                request.getRequestRef(),
                request.getStatus().name(),
                request.getCreatedAt()
        );
    }

    private AgentTodaySummary buildTodaySummary(List<TransactionEntity> todayTransactions) {
        BigDecimal commissionEarnedToday = BigDecimal.ZERO;
        BigDecimal cashInVolume = BigDecimal.ZERO;
        long cashInCount = 0;
        BigDecimal cashOutVolume = BigDecimal.ZERO;
        long cashOutCount = 0;

        for (TransactionEntity transaction : todayTransactions) {
            commissionEarnedToday = commissionEarnedToday.add(transaction.getCommission());

            if (TransactionType.CASH_IN.name().equals(transaction.getTransactionType())) {
                cashInVolume = cashInVolume.add(transaction.getAmount());
                cashInCount++;
            } else if (TransactionType.CASH_OUT.name().equals(transaction.getTransactionType())) {
                cashOutVolume = cashOutVolume.add(transaction.getAmount());
                cashOutCount++;
            }
        }

        return new AgentTodaySummary(
                normalizeScale(commissionEarnedToday),
                todayTransactions.size(),
                normalizeScale(cashInVolume),
                cashInCount,
                normalizeScale(cashOutVolume),
                cashOutCount
        );
    }

    private List<AgentRecentTransaction> buildRecentTransactions(List<UUID> walletIds) {
        Pageable top5 = PageRequest.of(0, RECENT_TRANSACTIONS_LIMIT);
        List<TransactionEntity> transactions = transactionRepository.findRecentByWalletIdIn(walletIds, top5);

        return transactions.stream()
                .map(transaction -> toRecentTransaction(transaction, walletIds))
                .toList();
    }

    private AgentRecentTransaction toRecentTransaction(TransactionEntity transaction, List<UUID> walletIds) {
        WalletEntity counterpartyWallet = walletIds.contains(transaction.getSenderWallet().getId())
                ? transaction.getReceiverWallet()
                : transaction.getSenderWallet();

        return new AgentRecentTransaction(
                transaction.getReferenceNo(),
                transaction.getTransactionType(),
                counterpartyName(counterpartyWallet),
                maskAccountNumber(counterpartyWallet.getAccountNumber()),
                transaction.getAmount(),
                transaction.getCommission(),
                transaction.getStatus(),
                transaction.getCreatedAt()
        );
    }

    // ========= The customer Name that an agent serve the transaction ============= //
    private String counterpartyName(WalletEntity counterpartyWallet) {
        UserEntity user = counterpartyWallet.getUser();
        return customerRepository.findByUserId(user.getId())
                .map(this::fullName)
                .orElse(counterpartyWallet.getAccountName());
    }

    private String fullName(CustomerEntity customer) {
        StringBuilder name = new StringBuilder(customer.getFirstName());
        if (customer.getMiddleName() != null && !customer.getMiddleName().isBlank()) {
            name.append(" ").append(customer.getMiddleName());
        }
        name.append(" ").append(customer.getLastName());
        return name.toString();
    }

    private static String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() <= 8) {
            return accountNumber;
        }
        return accountNumber.substring(0, 4) + "****" + accountNumber.substring(accountNumber.length() - 4);
    }

    private static BigDecimal normalizeScale(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
