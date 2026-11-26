package com.application.e_wallet.agent.activity;

import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.transaction.entity.TransactionEntity;
import com.application.e_wallet.transaction.repository.TransactionRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/** The agent's till history: what they see, what is masked, and that another agent's references stay hidden. */
@ExtendWith(MockitoExtension.class)
class AgentActivityServiceTest {

    @Mock private WalletRepository walletRepository;
    @Mock private TransactionRepository transactionRepository;
    @InjectMocks private AgentActivityService service;

    private UserEntity agentUser, customerUser;
    private WalletEntity agentFloat, customerWallet, otherFloat;
    private TransactionEntity cashIn, withdrawal;

    @BeforeEach
    void setUp() {
        agentUser = UserEntity.builder().id(UUID.randomUUID()).phone("+255744000111").build();
        customerUser = UserEntity.builder().id(UUID.randomUUID()).phone("+255712345678").build();
        agentFloat = WalletEntity.builder().id(UUID.randomUUID()).user(agentUser).walletType(WalletType.AGENT_FLOAT).accountNumber("200000000001").accountName("Mrema Shop — Float").build();
        otherFloat = WalletEntity.builder().id(UUID.randomUUID()).walletType(WalletType.AGENT_FLOAT).accountNumber("200000000002").build();
        customerWallet = WalletEntity.builder().id(UUID.randomUUID()).user(customerUser).walletType(WalletType.CUSTOMER_MAIN).accountNumber("038124415297").accountName("Amina Said — Main").build();
        cashIn = TransactionEntity.builder().id(UUID.randomUUID()).referenceNo("TXN-1").senderWallet(agentFloat).receiverWallet(customerWallet)
                .amount(new BigDecimal("50000")).fee(BigDecimal.ZERO).commission(new BigDecimal("200")).transactionType("CASH_IN").status("COMPLETED").createdAt(OffsetDateTime.now()).build();
        withdrawal = TransactionEntity.builder().id(UUID.randomUUID()).referenceNo("WDR-1").senderWallet(customerWallet).receiverWallet(agentFloat)
                .amount(new BigDecimal("30000")).fee(new BigDecimal("300")).commission(new BigDecimal("200")).matchCode("4821").transactionType("CASH_OUT").status("COMPLETED").createdAt(OffsetDateTime.now()).build();
        lenient().when(walletRepository.findByUserIdAndWalletType(agentUser.getId(), WalletType.AGENT_FLOAT)).thenReturn(Optional.of(agentFloat));
    }

    @Test
    void theListShowsTheCustomerWithAMaskedPhoneAndTheAccountNumber() {
        lenient().when(transactionRepository.findAgentActivity(eq(agentFloat.getId()), any(), any(), any(), anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(cashIn, withdrawal)));
        lenient().when(transactionRepository.findAgentActivityBetween(eq(agentFloat.getId()), any(), any(), any())).thenReturn(List.of(cashIn, withdrawal));

        AgentTransactionPage page = service.list(agentUser.getId(), null, null, null, null, 0, 20);

        assertThat(page.items()).hasSize(2);
        AgentTransactionRow row = page.items().get(0);
        assertThat(row.customerName()).isEqualTo("Amina Said");
        assertThat(row.customerAccount()).isEqualTo("038124415297");
        assertThat(row.customerPhone()).doesNotContain("712345678").contains("•");
        assertThat(page.kpis().todayCount()).isEqualTo(2);
        assertThat(page.kpis().cashInToday()).isEqualTo(1);
        assertThat(page.kpis().withdrawalsToday()).isEqualTo(1);
        assertThat(page.kpis().commissionToday()).isEqualByComparingTo("400");
    }

    @Test
    void theTypeFilterOnlyAllowsTillTypes() {
        lenient().when(transactionRepository.findAgentActivity(any(), any(), any(), any(), anyString(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        service.list(agentUser.getId(), "BANK_TOPUP", null, null, null, 0, 20);

        @SuppressWarnings("unchecked") ArgumentCaptor<java.util.Collection<String>> types = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(transactionRepository).findAgentActivity(any(), types.capture(), any(), any(), anyString(), any(Pageable.class));
        assertThat(types.getValue()).containsExactlyInAnyOrder("CASH_IN", "CASH_OUT");
    }

    @Test
    void theDetailShowsTheFullPhone() {
        lenient().when(transactionRepository.findByReferenceNo("WDR-1")).thenReturn(Optional.of(withdrawal));
        AgentTransactionRow row = service.detail(agentUser.getId(), "WDR-1");
        assertThat(row.customerPhone()).isEqualTo("+255712345678");
        assertThat(row.customerAccount()).isEqualTo("038124415297");
        assertThat(row.type()).isEqualTo("CASH_OUT");
    }

    @Test
    void anotherAgentsReferenceAnswersNotFound() {
        TransactionEntity theirs = TransactionEntity.builder().id(UUID.randomUUID()).referenceNo("TXN-9").senderWallet(otherFloat).receiverWallet(customerWallet)
                .amount(BigDecimal.TEN).transactionType("CASH_IN").status("COMPLETED").build();
        lenient().when(transactionRepository.findByReferenceNo("TXN-9")).thenReturn(Optional.of(theirs));
        lenient().when(transactionRepository.findByReferenceNo("TRF-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(agentUser.getId(), "TXN-9")).isInstanceOf(BadRequestException.class).hasMessage("Transaction not found");
        assertThatThrownBy(() -> service.detail(agentUser.getId(), "TRF-1")).isInstanceOf(BadRequestException.class).hasMessage("Transaction not found");
    }

    @Test
    void aBankMovementOfTheFloatIsNotATillTransaction() {
        TransactionEntity bank = TransactionEntity.builder().id(UUID.randomUUID()).referenceNo("FBT-1").senderWallet(otherFloat).receiverWallet(agentFloat)
                .amount(BigDecimal.TEN).transactionType("FLOAT_BANK_TOPUP").status("COMPLETED").build();
        lenient().when(transactionRepository.findByReferenceNo("FBT-1")).thenReturn(Optional.of(bank));
        assertThatThrownBy(() -> service.detail(agentUser.getId(), "FBT-1")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void theLiveFeedListsWithdrawalsWithTheCodeAndAMaskedPhone() {
        lenient().when(transactionRepository.findLiveWithdrawals(eq(agentFloat.getId()), any(), any(Pageable.class))).thenReturn(List.of(withdrawal));
        List<LiveWithdrawal> feed = service.live(agentUser.getId(), 15);
        assertThat(feed).hasSize(1);
        assertThat(feed.get(0).matchCode()).isEqualTo("4821");
        assertThat(feed.get(0).customerPhone()).doesNotContain("712345678");
        assertThat(feed.get(0).commission()).isEqualByComparingTo("200");
    }

    @Test
    void theWeekHasSevenDaysEvenWhenQuiet() {
        lenient().when(transactionRepository.findAgentActivityBetween(eq(agentFloat.getId()), any(), any(), any())).thenReturn(List.of(cashIn, withdrawal));
        List<WeeklyDay> week = service.weekly(agentUser.getId());
        assertThat(week).hasSize(7);
        WeeklyDay today = week.get(6);
        assertThat(today.cashInCount()).isEqualTo(1);
        assertThat(today.cashInAmount()).isEqualByComparingTo("50000");
        assertThat(today.withdrawalCount()).isEqualTo(1);
        assertThat(week.get(0).cashInCount()).isZero();
    }
}
