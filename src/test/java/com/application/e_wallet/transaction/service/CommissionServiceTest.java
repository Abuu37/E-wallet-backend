package com.application.e_wallet.transaction.service;

import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.ledger.dto.JournalLineRequest;
import com.application.e_wallet.ledger.entity.EntryType;
import com.application.e_wallet.ledger.entity.LedgerTransactionType;
import com.application.e_wallet.ledger.service.LedgerService;
import com.application.e_wallet.transaction.entity.CommissionOwedEntity;
import com.application.e_wallet.transaction.entity.TransactionEntity;
import com.application.e_wallet.transaction.repository.CommissionOwedRepository;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommissionServiceTest {

    @Mock private WalletRepository walletRepository;
    @Mock private CommissionOwedRepository owedRepository;
    @Mock private LedgerService ledgerService;
    @InjectMocks private CommissionService service;

    private WalletEntity pool;
    private UserEntity actor;

    private static WalletEntity wallet(WalletType type, String balance) {
        return WalletEntity.builder().id(UUID.randomUUID()).walletType(type).balance(new BigDecimal(balance)).currency("TZS").status(WalletStatus.ACTIVE).build();
    }

    private AgentEntity agent(WalletEntity commissionWallet, String number) {
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).build();
        lenient().when(walletRepository.findByUserIdAndWalletTypeForUpdate(user.getId(), WalletType.AGENT_COMMISSION)).thenReturn(Optional.of(commissionWallet));
        return AgentEntity.builder().id(UUID.randomUUID()).user(user).agentNumber(number).build();
    }

    private static CommissionOwedEntity owed(AgentEntity agent, String amount) {
        return CommissionOwedEntity.builder().agent(agent).amount(new BigDecimal(amount)).build();
    }

    @BeforeEach
    void setUp() {
        pool = wallet(WalletType.COMMISSION, "0.00");
        actor = UserEntity.builder().id(UUID.randomUUID()).build();
        lenient().when(walletRepository.findByWalletTypeForUpdate(WalletType.COMMISSION)).thenReturn(Optional.of(pool));
    }

    @Test
    void thePoolCanPayWhenItHoldsAtLeastTheCommission() {
        pool.setBalance(new BigDecimal("200"));

        assertThat(service.poolCanPay(pool, new BigDecimal("200"))).isTrue();
        assertThat(service.poolCanPay(pool, new BigDecimal("200.01"))).isFalse();
        assertThat(service.poolCanPay(pool, BigDecimal.ZERO)).isTrue();
    }

    @Test
    void anEmptyPoolOwesTheCommissionInsteadOfFailing() {
        AgentEntity agent = agent(wallet(WalletType.AGENT_COMMISSION, "0"), "AG-10234");

        service.recordOwed(agent, TransactionEntity.builder().referenceNo("WDR-1").build(), new BigDecimal("200"));

        verify(owedRepository).save(argThat(o -> o.getAmount().compareTo(new BigDecimal("200")) == 0 && o.getAgent() == agent));
    }

    @Test
    void zeroCommissionIsNeverRecordedAsOwed() {
        service.recordOwed(agent(wallet(WalletType.AGENT_COMMISSION, "0"), "AG-1"), null, BigDecimal.ZERO);

        verifyNoInteractions(owedRepository);
    }

    @Test
    void aRefillPaysOwedCommissionOldestFirstForAsLongAsThePoolCoversIt() {
        pool.setBalance(new BigDecimal("500"));
        WalletEntity w1 = wallet(WalletType.AGENT_COMMISSION, "0");
        WalletEntity w2 = wallet(WalletType.AGENT_COMMISSION, "0");
        WalletEntity w3 = wallet(WalletType.AGENT_COMMISSION, "0");
        CommissionOwedEntity first = owed(agent(w1, "AG-1"), "200");
        CommissionOwedEntity second = owed(agent(w2, "AG-2"), "200");
        CommissionOwedEntity third = owed(agent(w3, "AG-3"), "200");
        when(owedRepository.findUnpaidOldestFirst()).thenReturn(List.of(first, second, third));

        int paid = service.settleOwed(actor);

        assertThat(paid).isEqualTo(2);
        assertThat(pool.getBalance()).isEqualByComparingTo("100");
        assertThat(w1.getBalance()).isEqualByComparingTo("200");
        assertThat(w2.getBalance()).isEqualByComparingTo("200");
        assertThat(w3.getBalance()).isEqualByComparingTo("0");
        assertThat(first.getPaidAt()).isNotNull();
        assertThat(second.getPaidAt()).isNotNull();
        assertThat(third.getPaidAt()).as("the pool ran short, so the newest stays owed").isNull();
        verify(ledgerService, org.mockito.Mockito.times(2)).postJournal(eq(LedgerTransactionType.COMMISSION_SETTLE), any(), eq(actor), argThat((List<JournalLineRequest> lines) ->
                lines.size() == 2 && lines.stream().anyMatch(l -> l.walletId().equals(pool.getId()) && l.entryType() == EntryType.DEBIT)));
    }

    @Test
    void anEmptyPoolSettlesNothing() {
        pool.setBalance(BigDecimal.ZERO);
        CommissionOwedEntity only = owed(agent(wallet(WalletType.AGENT_COMMISSION, "0"), "AG-1"), "200");
        when(owedRepository.findUnpaidOldestFirst()).thenReturn(List.of(only));

        assertThat(service.settleOwed(actor)).isZero();

        verify(ledgerService, never()).postJournal(any(), any(), any(), any());
        assertThat(only.getPaidAt()).isNull();
    }

    @Test
    void nothingOwedMeansNothingHappens() {
        when(owedRepository.findUnpaidOldestFirst()).thenReturn(List.of());

        assertThat(service.settleOwed(actor)).isZero();

        verifyNoInteractions(ledgerService);
    }
}
