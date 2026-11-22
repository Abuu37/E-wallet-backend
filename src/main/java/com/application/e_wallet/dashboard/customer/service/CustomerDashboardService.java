package com.application.e_wallet.dashboard.customer.service;

import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.customer.dto.CustomerTransactionItem;
import com.application.e_wallet.customer.dto.CustomerWalletSummary;
import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.dashboard.customer.dto.CustomerDashboardOverviewResponse;
import com.application.e_wallet.dashboard.customer.dto.CustomerDashboardProfile;
import com.application.e_wallet.dashboard.customer.dto.CustomerMonthlyCashFlow;
import com.application.e_wallet.dashboard.customer.dto.CustomerPendingActivity;
import com.application.e_wallet.transaction.entity.TransactionEntity;
import com.application.e_wallet.transaction.repository.TransactionRepository;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.application.e_wallet.dashboard.DashboardRangeSupport.DASHBOARD_ZONE;

@Service
@RequiredArgsConstructor
public class CustomerDashboardService {

    private static final int RECENT_TRANSACTIONS_LIMIT = 5;

    private final CustomerRepository customerRepository;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    @Transactional(readOnly = true)
    public CustomerDashboardOverviewResponse getOverview(UUID callerUserId) {
        CustomerEntity customer = customerRepository.findByUserId(callerUserId)
                .orElseThrow(() -> new BadRequestException("No customer profile for this account"));

        WalletEntity wallet = walletRepository.findByUserIdAndWalletType(callerUserId, WalletType.CUSTOMER_MAIN)
                .orElseThrow(() -> new IllegalStateException("Customer wallet not initialized"));

        return new CustomerDashboardOverviewResponse(
                OffsetDateTime.now(DASHBOARD_ZONE),
                buildProfile(customer),
                buildWalletSummary(wallet),
                buildMonthlyCashFlow(wallet.getId()),
                buildPendingActivity(wallet.getId()),
                buildRecentTransactions(wallet.getId())
        );
    }

    private CustomerDashboardProfile buildProfile(CustomerEntity customer) {
        return new CustomerDashboardProfile(customer.getFirstName(), customer.getStatus().name());
    }

    private CustomerWalletSummary buildWalletSummary(WalletEntity wallet) {
        return new CustomerWalletSummary(
                wallet.getAccountNumber(),
                wallet.getWalletType().name(),
                wallet.getCurrency(),
                wallet.getBalance(),
                wallet.getStatus().name());
    }

    // Package-private so tests can call this section builder directly without going through
    // the public getOverview() entry point. Calendar-month-to-date, in the dashboard's fixed
    // zone - open-ended up to now, same "today"/"week" pattern the admin/agent dashboards use.
    CustomerMonthlyCashFlow buildMonthlyCashFlow(UUID walletId) {
        OffsetDateTime now = OffsetDateTime.now(DASHBOARD_ZONE);
        OffsetDateTime startOfMonth = now.toLocalDate().withDayOfMonth(1).atStartOfDay(DASHBOARD_ZONE).toOffsetDateTime();

        String monthLabel = now.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        BigDecimal moneyIn = transactionRepository.sumAmountInByWalletIdAndCreatedAtBetween(walletId, startOfMonth, now);
        BigDecimal moneyOut = transactionRepository.sumAmountOutByWalletIdAndCreatedAtBetween(walletId, startOfMonth, now);
        long transactionCount = transactionRepository.countByWalletIdAndCreatedAtBetween(walletId, startOfMonth, now);

        return new CustomerMonthlyCashFlow(monthLabel, moneyIn, moneyOut, transactionCount);
    }

    // Package-private so tests can call this section builder directly without going through
    // the public getOverview() entry point.
    CustomerPendingActivity buildPendingActivity(UUID walletId) {
        long count = transactionRepository.countPendingByReceiverWalletId(walletId);
        BigDecimal amount = transactionRepository.sumPendingAmountByReceiverWalletId(walletId);
        return new CustomerPendingActivity(count, amount);
    }

    private List<CustomerTransactionItem> buildRecentTransactions(UUID walletId) {
        List<TransactionEntity> recent = transactionRepository.findRecentByWalletIdIn(
                List.of(walletId), PageRequest.of(0, RECENT_TRANSACTIONS_LIMIT));

        return recent.stream()
                .map(transaction -> new CustomerTransactionItem(
                        transaction.getReferenceNo(),
                        transaction.getTransactionType(),
                        transaction.getSenderWallet().getId().equals(walletId) ? "DEBIT" : "CREDIT",
                        transaction.getAmount(),
                        transaction.getFee(),
                        transaction.getStatus(),
                        transaction.getCreatedAt()))
                .toList();
    }
}
