package com.application.e_wallet.dashboard.customer.service;

import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.customer.entity.CustomerStatus;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.dashboard.customer.dto.CustomerDashboardOverviewResponse;
import com.application.e_wallet.dashboard.customer.dto.CustomerMonthlyCashFlow;
import com.application.e_wallet.dashboard.customer.dto.CustomerPendingActivity;
import com.application.e_wallet.transaction.entity.TransactionEntity;
import com.application.e_wallet.transaction.repository.TransactionRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletStatus;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerDashboardServiceTest {

    @Mock private CustomerRepository customerRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private TransactionRepository transactionRepository;
    @InjectMocks private CustomerDashboardService customerDashboardService;

    private UUID customerUserId;
    private CustomerEntity customer;
    private WalletEntity wallet;

    @BeforeEach
    void setUp() {
        customerUserId = UUID.randomUUID();
        UserEntity user = UserEntity.builder().id(customerUserId).build();

        customer = CustomerEntity.builder()
                .id(UUID.randomUUID())
                .user(user)
                .firstName("Ibrahim")
                .lastName("Mohammed")
                .status(CustomerStatus.ACTIVE)
                .build();

        wallet = WalletEntity.builder()
                .id(UUID.randomUUID())
                .accountNumber("033473180342")
                .walletType(WalletType.CUSTOMER_MAIN)
                .currency("TZS")
                .balance(new BigDecimal("7700.00"))
                .status(WalletStatus.ACTIVE)
                .build();

        lenient().when(customerRepository.findByUserId(customerUserId)).thenReturn(Optional.of(customer));
        lenient().when(walletRepository.findByUserIdAndWalletType(customerUserId, WalletType.CUSTOMER_MAIN))
                .thenReturn(Optional.of(wallet));
        lenient().when(transactionRepository.sumAmountInByWalletIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        lenient().when(transactionRepository.sumAmountOutByWalletIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        lenient().when(transactionRepository.countByWalletIdAndCreatedAtBetween(any(), any(), any())).thenReturn(0L);
        lenient().when(transactionRepository.countPendingByReceiverWalletId(any())).thenReturn(0L);
        lenient().when(transactionRepository.sumPendingAmountByReceiverWalletId(any())).thenReturn(BigDecimal.ZERO);
        lenient().when(transactionRepository.findRecentByWalletIdIn(anyList(), any(Pageable.class))).thenReturn(List.of());
    }

    @Test
    void getOverviewThrowsWhenCallerHasNoCustomerProfile() {
        when(customerRepository.findByUserId(customerUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerDashboardService.getOverview(customerUserId))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void getOverviewThrowsWhenCustomerHasNoWalletYet() {
        when(walletRepository.findByUserIdAndWalletType(customerUserId, WalletType.CUSTOMER_MAIN))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerDashboardService.getOverview(customerUserId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void profileUsesFirstNameOnlyAndCurrentStatus() {
        CustomerDashboardOverviewResponse response = customerDashboardService.getOverview(customerUserId);

        assertThat(response.profile().firstName()).isEqualTo("Ibrahim");
        assertThat(response.profile().status()).isEqualTo("ACTIVE");
    }

    @Test
    void walletSummaryMatchesTheCustomersMainWallet() {
        CustomerDashboardOverviewResponse response = customerDashboardService.getOverview(customerUserId);

        assertThat(response.wallet().accountNumber()).isEqualTo("033473180342");
        assertThat(response.wallet().walletType()).isEqualTo("CUSTOMER_MAIN");
        assertThat(response.wallet().currency()).isEqualTo("TZS");
        assertThat(response.wallet().availableBalance()).isEqualByComparingTo("7700.00");
        assertThat(response.wallet().status()).isEqualTo("ACTIVE");
    }

    @Test
    void monthlyCashFlowSumsCompletedTransactionsInTheCurrentMonthWindow() {
        when(transactionRepository.sumAmountInByWalletIdAndCreatedAtBetween(eq(wallet.getId()), any(), any()))
                .thenReturn(new BigDecimal("10000.00"));
        when(transactionRepository.sumAmountOutByWalletIdAndCreatedAtBetween(eq(wallet.getId()), any(), any()))
                .thenReturn(new BigDecimal("2300.00"));
        when(transactionRepository.countByWalletIdAndCreatedAtBetween(eq(wallet.getId()), any(), any()))
                .thenReturn(3L);

        CustomerMonthlyCashFlow result = customerDashboardService.buildMonthlyCashFlow(wallet.getId());

        String expectedLabel = OffsetDateTime.now().getMonth()
                .getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH);
        assertThat(result.monthLabel()).isEqualTo(expectedLabel);
        assertThat(result.moneyIn()).isEqualByComparingTo("10000.00");
        assertThat(result.moneyOut()).isEqualByComparingTo("2300.00");
        assertThat(result.transactionCount()).isEqualTo(3);
    }

    @Test
    void pendingActivityReportsInProgressIncomingTransactionsOnly() {
        when(transactionRepository.countPendingByReceiverWalletId(wallet.getId())).thenReturn(1L);
        when(transactionRepository.sumPendingAmountByReceiverWalletId(wallet.getId()))
                .thenReturn(new BigDecimal("5000.00"));

        CustomerPendingActivity result = customerDashboardService.buildPendingActivity(wallet.getId());

        assertThat(result.count()).isEqualTo(1);
        assertThat(result.amount()).isEqualByComparingTo("5000.00");
    }

    @Test
    void recentTransactionsMarksDirectionRelativeToTheCustomersOwnWallet() {
        WalletEntity counterparty = WalletEntity.builder().id(UUID.randomUUID()).build();
        TransactionEntity incoming = TransactionEntity.builder()
                .referenceNo("TXN-1").transactionType("CASH_IN").status("COMPLETED")
                .senderWallet(counterparty).receiverWallet(wallet)
                .amount(new BigDecimal("5000.00")).fee(BigDecimal.ZERO)
                .createdAt(OffsetDateTime.now()).build();
        TransactionEntity outgoing = TransactionEntity.builder()
                .referenceNo("TXN-2").transactionType("CASH_OUT").status("COMPLETED")
                .senderWallet(wallet).receiverWallet(counterparty)
                .amount(new BigDecimal("1000.00")).fee(new BigDecimal("300.00"))
                .createdAt(OffsetDateTime.now()).build();
        when(transactionRepository.findRecentByWalletIdIn(List.of(wallet.getId()), org.springframework.data.domain.PageRequest.of(0, 5)))
                .thenReturn(List.of(incoming, outgoing));

        CustomerDashboardOverviewResponse response = customerDashboardService.getOverview(customerUserId);

        assertThat(response.recentTransactions()).hasSize(2);
        assertThat(response.recentTransactions().get(0).direction()).isEqualTo("CREDIT");
        assertThat(response.recentTransactions().get(1).direction()).isEqualTo("DEBIT");
        assertThat(response.recentTransactions().get(1).fee()).isEqualByComparingTo("300.00");
    }
}
