package com.application.e_wallet.dashboard.agent.service;

import com.application.e_wallet.agent.dto.FloatHealth;
import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.entity.AgentStatus;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.dashboard.DashboardRange;
import com.application.e_wallet.dashboard.DashboardRangeSupport;
import com.application.e_wallet.dashboard.agent.dto.AgentDashboardOverviewResponse;
import com.application.e_wallet.dashboard.agent.dto.AgentTransactionVolume;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.transaction.entity.TransactionEntity;
import com.application.e_wallet.transaction.entity.TransactionStatus;
import com.application.e_wallet.transaction.repository.TransactionRepository;
import com.application.e_wallet.treasury.entity.RequestStatus;
import com.application.e_wallet.treasury.entity.RequestType;
import com.application.e_wallet.treasury.entity.TreasuryRequestEntity;
import com.application.e_wallet.treasury.repository.TreasuryRequestRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.wallet.entity.WalletEntity;
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
import java.time.LocalDate;
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
class AgentDashboardServiceTest {

    @Mock private AgentRepository agentRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private TreasuryRequestRepository treasuryRequestRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private CustomerRepository customerRepository;
    @InjectMocks private AgentDashboardService agentDashboardService;

    private UUID agentUserId;
    private UUID agentId;
    private AgentEntity agent;
    private WalletEntity floatWallet;
    private WalletEntity commissionWallet;

    @BeforeEach
    void setUp() {
        agentUserId = UUID.randomUUID();
        agentId = UUID.randomUUID();

        UserEntity agentUser = UserEntity.builder().id(agentUserId).build();

        agent = AgentEntity.builder()
                .id(agentId)
                .user(agentUser)
                .agentNumber("40625")
                .businessName("Ilala Mchikichini Wakala")
                .agentStatus(AgentStatus.ACTIVE)
                .build();

        floatWallet = WalletEntity.builder()
                .id(UUID.randomUUID())
                .accountNumber("019482716350")
                .balance(new BigDecimal("750000.00"))
                .currency("TZS")
                .build();

        commissionWallet = WalletEntity.builder()
                .id(UUID.randomUUID())
                .accountNumber("025819304721")
                .balance(new BigDecimal("18500.00"))
                .currency("TZS")
                .build();

        lenient().when(agentRepository.findByUserId(agentUserId)).thenReturn(Optional.of(agent));
        lenient().when(walletRepository.findByUserIdAndWalletType(agentUserId, WalletType.AGENT_FLOAT))
                .thenReturn(Optional.of(floatWallet));
        lenient().when(walletRepository.findByUserIdAndWalletType(agentUserId, WalletType.AGENT_COMMISSION))
                .thenReturn(Optional.of(commissionWallet));
        lenient().when(treasuryRequestRepository.countByAgent_IdAndStatus(agentId, RequestStatus.PENDING)).thenReturn(0L);
        lenient().when(treasuryRequestRepository.findAllByAgent_IdOrderByCreatedAtDesc(eq(agentId), any())).thenReturn(List.of());
        lenient().when(transactionRepository.findByStatusAndCreatedAtBetweenAndWalletIdIn(any(), any(), any(), anyList()))
                .thenReturn(List.of());
        lenient().when(transactionRepository.findRecentByWalletIdIn(anyList(), any(Pageable.class))).thenReturn(List.of());
        lenient().when(transactionRepository.countByWalletIdIn(anyList())).thenReturn(0L);
    }

    @Test
    void getOverviewThrowsWhenCallerHasNoAgentProfile() {
        when(agentRepository.findByUserId(agentUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> agentDashboardService.getOverview(agentUserId))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void agentProfileHasNoTerminalIdFieldAndUsesAgentNumberAndBusinessName() {
        AgentDashboardOverviewResponse response = agentDashboardService.getOverview(agentUserId);

        assertThat(response.agentProfile().agentCode()).isEqualTo("40625");
        assertThat(response.agentProfile().businessName()).isEqualTo("Ilala Mchikichini Wakala");
        assertThat(response.agentProfile().status()).isEqualTo("ACTIVE");
    }

    @Test
    void floatAccountReportsLowWhenBalanceBelowLowThreshold() {
        floatWallet.setBalance(new BigDecimal("50000.00"));

        AgentDashboardOverviewResponse response = agentDashboardService.getOverview(agentUserId);

        assertThat(response.wallets().floatAccount().floatHealth()).isEqualTo(FloatHealth.LOW);
    }

    @Test
    void floatAccountReportsCriticalWhenBalanceIsZeroOrBelow() {
        floatWallet.setBalance(BigDecimal.ZERO);

        AgentDashboardOverviewResponse response = agentDashboardService.getOverview(agentUserId);

        assertThat(response.wallets().floatAccount().floatHealth()).isEqualTo(FloatHealth.CRITICAL);
    }

    @Test
    void floatAccountReportsHealthyWhenBalanceAtOrAboveLowThreshold() {
        AgentDashboardOverviewResponse response = agentDashboardService.getOverview(agentUserId);

        assertThat(response.wallets().floatAccount().floatHealth()).isEqualTo(FloatHealth.HEALTHY);
        assertThat(response.wallets().floatAccount().accountNumber()).isEqualTo("019482716350");
        assertThat(response.wallets().commissionAccount().accountNumber()).isEqualTo("025819304721");
    }

    @Test
    void floatRequestStatusUsesInternalReferenceNotExternalReference() {
        TreasuryRequestEntity request = TreasuryRequestEntity.builder()
                .id(UUID.randomUUID())
                .requestRef("REQ-48AB12")
                .agent(agent)
                .amount(new BigDecimal("500000.00"))
                .reason("float running low")
                .status(RequestStatus.PENDING)
                .requestType(RequestType.TOPUP)
                .externalReference("CI261117.0845.D77112")
                .createdAt(OffsetDateTime.parse("2026-11-17T08:47:00+03:00"))
                .build();

        when(treasuryRequestRepository.countByAgent_IdAndStatus(agentId, RequestStatus.PENDING)).thenReturn(1L);
        when(treasuryRequestRepository.findAllByAgent_IdOrderByCreatedAtDesc(eq(agentId), any())).thenReturn(List.of(request));

        AgentDashboardOverviewResponse response = agentDashboardService.getOverview(agentUserId);

        assertThat(response.floatRequestStatus().pendingCount()).isEqualTo(1);
        assertThat(response.floatRequestStatus().latestRequest().internalReference()).isEqualTo("REQ-48AB12");
        assertThat(response.floatRequestStatus().latestRequest().status()).isEqualTo("PENDING");
    }

    @Test
    void floatRequestStatusLatestRequestIsNullWhenAgentHasNoRequests() {
        AgentDashboardOverviewResponse response = agentDashboardService.getOverview(agentUserId);

        assertThat(response.floatRequestStatus().latestRequest()).isNull();
    }

    @Test
    void todaySummarySumsCommissionAndSplitsCashInAndCashOutFromScopedTransactionsOnly() {
        TransactionEntity cashIn = transaction("CASH_IN", floatWallet, otherWallet(), "150000.00", "450.00");
        TransactionEntity cashOut = transaction("CASH_OUT", otherWallet(), commissionWallet, "80000.00", "0.00");
        when(transactionRepository.findByStatusAndCreatedAtBetweenAndWalletIdIn(
                eq(TransactionStatus.COMPLETED.name()), any(), any(), anyList()))
                .thenReturn(List.of(cashIn, cashOut));

        AgentDashboardOverviewResponse response = agentDashboardService.getOverview(agentUserId);

        assertThat(response.todaySummary().totalTransactionsCount()).isEqualTo(2);
        assertThat(response.todaySummary().commissionEarnedToday()).isEqualByComparingTo("450.00");
        assertThat(response.todaySummary().cashInVolume()).isEqualByComparingTo("150000.00");
        assertThat(response.todaySummary().cashInCount()).isEqualTo(1);
        assertThat(response.todaySummary().cashOutVolume()).isEqualByComparingTo("80000.00");
        assertThat(response.todaySummary().cashOutCount()).isEqualTo(1);
    }

    @Test
    void transactionVolumeDefaultsToTodayRangeAndSplitsCashInAndCashOut() {
        TransactionEntity cashIn = transaction("CASH_IN", floatWallet, otherWallet(), "150000.00", "450.00");
        TransactionEntity cashOut = transaction("CASH_OUT", otherWallet(), commissionWallet, "80000.00", "0.00");
        when(transactionRepository.findByStatusAndCreatedAtBetweenAndWalletIdIn(
                eq(TransactionStatus.COMPLETED.name()), any(), any(), anyList()))
                .thenReturn(List.of(cashIn, cashOut));

        AgentDashboardOverviewResponse response = agentDashboardService.getOverview(agentUserId);

        AgentTransactionVolume volume = response.transactionVolume();
        assertThat(volume.range()).isEqualTo("TODAY");
        assertThat(volume.totalVolume()).isEqualByComparingTo("230000.00");
        assertThat(volume.totalCount()).isEqualTo(2);
        assertThat(volume.cashIn().count()).isEqualTo(1);
        assertThat(volume.cashIn().volume()).isEqualByComparingTo("150000.00");
        assertThat(volume.cashOut().count()).isEqualTo(1);
        assertThat(volume.cashOut().volume()).isEqualByComparingTo("80000.00");
    }

    @Test
    void transactionVolumeSupportsWeekRangeWithDailyTrend() {
        AgentDashboardOverviewResponse response = agentDashboardService.getOverview(agentUserId, "week", null, null);

        AgentTransactionVolume volume = response.transactionVolume();
        assertThat(volume.range()).isEqualTo("WEEK");
        assertThat(volume.trend()).hasSize(7);
        LocalDate today = OffsetDateTime.now(DashboardRangeSupport.DASHBOARD_ZONE).toLocalDate();
        assertThat(volume.periodStart()).isEqualTo(today.minusDays(6));
        assertThat(volume.periodEnd()).isEqualTo(today);
    }

    @Test
    void transactionVolumeSupportsCustomRangeWithExplicitDates() {
        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 3);

        AgentDashboardOverviewResponse response = agentDashboardService.getOverview(agentUserId, "custom", from, to);

        AgentTransactionVolume volume = response.transactionVolume();
        assertThat(volume.range()).isEqualTo("CUSTOM");
        assertThat(volume.periodStart()).isEqualTo(from);
        assertThat(volume.periodEnd()).isEqualTo(to);
        assertThat(volume.trend()).hasSize(3);
    }

    @Test
    void getOverviewRejectsCustomRangeMissingFromOrTo() {
        assertThatThrownBy(() -> agentDashboardService.getOverview(agentUserId, "custom", null, null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void getOverviewRejectsAnInvalidRangeValue() {
        assertThatThrownBy(() -> agentDashboardService.getOverview(agentUserId, "fortnight", null, null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void buildTransactionVolumeOnlyQueriesThisAgentsOwnWallets() {
        DashboardRangeSupport.TransactionPeriod period = DashboardRangeSupport.resolvePeriod(DashboardRange.TODAY, null, null);
        List<UUID> walletIds = List.of(floatWallet.getId(), commissionWallet.getId());

        agentDashboardService.buildTransactionVolume(walletIds, DashboardRange.TODAY, period);

        org.mockito.Mockito.verify(transactionRepository).findByStatusAndCreatedAtBetweenAndWalletIdIn(
                eq(TransactionStatus.COMPLETED.name()), eq(period.window().start()), eq(period.window().end()), eq(walletIds));
    }

    @Test
    void summaryReportsCurrentBalancesLifetimeTransactionCountAndFloatHealth() {
        when(transactionRepository.countByWalletIdIn(List.of(floatWallet.getId(), commissionWallet.getId())))
                .thenReturn(37L);

        AgentDashboardOverviewResponse response = agentDashboardService.getOverview(agentUserId);

        assertThat(response.summary().totalFloatBalance()).isEqualByComparingTo(floatWallet.getBalance());
        assertThat(response.summary().totalCommissionBalance()).isEqualByComparingTo(commissionWallet.getBalance());
        assertThat(response.summary().totalTransactions()).isEqualTo(37L);
        assertThat(response.summary().floatHealth()).isEqualTo(FloatHealth.HEALTHY);
    }

    @Test
    void summaryReflectsLowFloatHealthWhenBalanceIsBelowThreshold() {
        floatWallet.setBalance(new BigDecimal("50000.00"));

        AgentDashboardOverviewResponse response = agentDashboardService.getOverview(agentUserId);

        assertThat(response.summary().floatHealth()).isEqualTo(FloatHealth.LOW);
    }

    @Test
    void recentTransactionsResolvesCustomerNameAndMasksTheCounterpartyAccountNumber() {
        WalletEntity customerWallet = WalletEntity.builder()
                .id(UUID.randomUUID())
                .accountNumber("037412340456")
                .accountName("fallback name")
                .user(UserEntity.builder().id(UUID.randomUUID()).build())
                .build();
        CustomerEntity customer = CustomerEntity.builder().firstName("Amina").lastName("Salum").build();

        TransactionEntity deposit = transaction("CASH_IN", floatWallet, customerWallet, "150000.00", "450.00");
        when(transactionRepository.findRecentByWalletIdIn(anyList(), any(Pageable.class))).thenReturn(List.of(deposit));
        when(customerRepository.findByUserId(customerWallet.getUser().getId())).thenReturn(Optional.of(customer));

        AgentDashboardOverviewResponse response = agentDashboardService.getOverview(agentUserId);

        assertThat(response.recentTransactions()).hasSize(1);
        var item = response.recentTransactions().get(0);
        assertThat(item.customerName()).isEqualTo("Amina Salum");
        assertThat(item.customerAccount()).isEqualTo("0374****0456");
    }

    @Test
    void recentTransactionsFallsBackToWalletDisplayNameWhenCounterpartyIsNotACustomer() {
        WalletEntity nonCustomerWallet = otherWallet();
        TransactionEntity transfer = transaction("CASH_OUT", floatWallet, nonCustomerWallet, "20000.00", "0.00");
        when(transactionRepository.findRecentByWalletIdIn(anyList(), any(Pageable.class))).thenReturn(List.of(transfer));
        when(customerRepository.findByUserId(nonCustomerWallet.getUser().getId())).thenReturn(Optional.empty());

        AgentDashboardOverviewResponse response = agentDashboardService.getOverview(agentUserId);

        assertThat(response.recentTransactions().get(0).customerName()).isEqualTo(nonCustomerWallet.getAccountName());
    }

    private WalletEntity otherWallet() {
        return WalletEntity.builder()
                .id(UUID.randomUUID())
                .accountNumber("099900001111")
                .accountName("Company Master Account")
                .user(UserEntity.builder().id(UUID.randomUUID()).build())
                .build();
    }

    private static TransactionEntity transaction(
            String type, WalletEntity sender, WalletEntity receiver, String amount, String commission) {
        return TransactionEntity.builder()
                .id(UUID.randomUUID())
                .referenceNo("TXN-" + UUID.randomUUID())
                .senderWallet(sender)
                .receiverWallet(receiver)
                .amount(new BigDecimal(amount))
                .fee(BigDecimal.ZERO)
                .commission(new BigDecimal(commission))
                .transactionType(type)
                .status(TransactionStatus.COMPLETED.name())
                .createdAt(OffsetDateTime.now())
                .build();
    }
}
