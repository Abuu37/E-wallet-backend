package com.application.e_wallet.bank.service;

import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.entity.AgentStatus;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.bank.dto.BankMoveRequest;
import com.application.e_wallet.bank.dto.BankMoveResponse;
import com.application.e_wallet.bank.entity.BankPostingEntity;
import com.application.e_wallet.bank.entity.LinkedBankEntity;
import com.application.e_wallet.bank.repository.BankPostingRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.ledger.dto.JournalLineRequest;
import com.application.e_wallet.ledger.entity.EntryType;
import com.application.e_wallet.ledger.entity.LedgerTransactionType;
import com.application.e_wallet.ledger.service.LedgerService;
import com.application.e_wallet.notification.NotificationKey;
import com.application.e_wallet.notification.Notifier;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The agent's own bank: float top-up with its daily cap, and sending float or commission to the bank. */
@ExtendWith(MockitoExtension.class)
class AgentBankServiceTest {

    @Mock private AgentRepository agentRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private BankPostingRepository postingRepository;
    @Mock private BankLinkService bankLinks;
    @Mock private BankPostingService postingService;
    @Mock private LedgerService ledgerService;
    @Mock private PasswordEncoder pinEncoder;
    @Mock private Notifier notifier;

    private AgentBankService service;
    private UserEntity agentUser;
    private AgentEntity agent;
    private WalletEntity floatWallet, commissionWallet, escrow;
    private LinkedBankEntity bank;

    private static WalletEntity wallet(UserEntity owner, WalletType type, String balance) {
        return WalletEntity.builder().id(UUID.randomUUID()).user(owner).walletType(type).accountNumber("WP-" + type)
                .balance(new BigDecimal(balance)).currency("TZS").status(WalletStatus.ACTIVE).build();
    }

    @BeforeEach
    void setUp() {
        service = new AgentBankService(agentRepository, walletRepository, transactionRepository, postingRepository, bankLinks, postingService,
                ledgerService, pinEncoder, notifier, new BigDecimal("5000000"));
        agentUser = UserEntity.builder().id(UUID.randomUUID()).phone("+255744000111").pinHash("agent-hash").build();
        agent = AgentEntity.builder().id(UUID.randomUUID()).user(agentUser).agentNumber("AG-10234").businessName("Mrema Shop").agentStatus(AgentStatus.ACTIVE).build();
        floatWallet = wallet(agentUser, WalletType.AGENT_FLOAT, "300000.00");
        commissionWallet = wallet(agentUser, WalletType.AGENT_COMMISSION, "12000.00");
        escrow = wallet(null, WalletType.ESCROW_BANK, "2000000.00");
        bank = LinkedBankEntity.builder().id(UUID.randomUUID()).userId(agentUser.getId()).institutionName("First Platypus Bank").accountMask("0000").nameMatched(true).build();

        lenient().when(agentRepository.findByUserId(agentUser.getId())).thenReturn(Optional.of(agent));
        lenient().when(walletRepository.findByUserIdAndWalletType(agentUser.getId(), WalletType.AGENT_FLOAT)).thenReturn(Optional.of(floatWallet));
        lenient().when(walletRepository.findByUserIdAndWalletType(agentUser.getId(), WalletType.AGENT_COMMISSION)).thenReturn(Optional.of(commissionWallet));
        lenient().when(walletRepository.findByWalletType(WalletType.ESCROW_BANK)).thenReturn(Optional.of(escrow));
        lenient().when(walletRepository.lockAllOrdered(anyCollection())).thenReturn(List.of(floatWallet, commissionWallet, escrow));
        lenient().when(bankLinks.ownBank(agentUser.getId(), bank.getId())).thenReturn(bank);
        lenient().when(bankLinks.requireUsable(bank)).thenReturn(bank);
        lenient().when(bankLinks.nameCheck(bank)).thenReturn(BankLinkService.MATCHED);
        lenient().when(bankLinks.availableTzs(bank)).thenReturn(new BigDecimal("2600000"));
        lenient().when(bankLinks.toBankCurrency(any())).thenReturn(new BigDecimal("100.00"));
        lenient().when(pinEncoder.matches("2580", "agent-hash")).thenReturn(true);
        lenient().when(transactionRepository.existsByReferenceNo(any())).thenReturn(false);
        lenient().when(transactionRepository.sumReceivedByTypeBetween(any(), eq("FLOAT_BANK_TOPUP"), any(), any())).thenReturn(BigDecimal.ZERO);
        lenient().when(transactionRepository.save(any(TransactionEntity.class))).thenAnswer(inv -> {
            TransactionEntity t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });
        lenient().when(postingRepository.save(any(BankPostingEntity.class))).thenAnswer(inv -> {
            BankPostingEntity p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
    }

    private BankMoveRequest move(String amount, String pin) {
        return new BankMoveRequest(bank.getId(), new BigDecimal(amount), pin);
    }

    // ------------------------------------------------------------------ float top-up from the bank
    @Test
    void topUp_issuesFloatFromEscrowWithABalancedJournal() {
        BankMoveResponse response = service.topUpFloat(move("1000000", "2580"), agentUser.getId());

        assertThat(floatWallet.getBalance()).isEqualByComparingTo("1300000.00");
        assertThat(escrow.getBalance()).isEqualByComparingTo("3000000.00");
        assertThat(response.status()).isEqualTo("COMPLETED");

        @SuppressWarnings("unchecked") ArgumentCaptor<List<JournalLineRequest>> lines = ArgumentCaptor.forClass(List.class);
        verify(ledgerService).postJournal(eq(LedgerTransactionType.FLOAT_BANK_TOPUP), any(), eq(agentUser), lines.capture());
        assertThat(lines.getValue()).containsExactlyInAnyOrder(
                new JournalLineRequest(escrow.getId(), EntryType.DEBIT, new BigDecimal("1000000")),
                new JournalLineRequest(floatWallet.getId(), EntryType.CREDIT, new BigDecimal("1000000")));
        verify(postingService).attemptAfterCommit(any());
        verify(notifier).notify(eq(NotificationKey.A3), any(), any());
    }

    @Test
    void topUp_overTheDailyCapIsRefusedWithAHintToRequestFloat() {
        when(transactionRepository.sumReceivedByTypeBetween(any(), eq("FLOAT_BANK_TOPUP"), any(), any())).thenReturn(new BigDecimal("4500000"));

        assertThatThrownBy(() -> service.topUpFloat(move("1000000", "2580"), agentUser.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("5,000,000").hasMessageContaining("500,000").hasMessageContaining("Request float");
        assertThat(floatWallet.getBalance()).isEqualByComparingTo("300000.00");
        verifyNoInteractions(ledgerService, postingService);
    }

    @Test
    void topUp_exactlyAtTheCapIsAllowed() {
        when(transactionRepository.sumReceivedByTypeBetween(any(), eq("FLOAT_BANK_TOPUP"), any(), any())).thenReturn(new BigDecimal("4000000"));
        service.topUpFloat(move("1000000", "2580"), agentUser.getId());
        assertThat(floatWallet.getBalance()).isEqualByComparingTo("1300000.00");
    }

    @Test
    void topUp_withAWrongPinMovesNothing() {
        assertThatThrownBy(() -> service.topUpFloat(move("1000000", "0000"), agentUser.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Wrong PIN");
        verifyNoInteractions(ledgerService, notifier, postingService);
    }

    @Test
    void topUp_refusesWhenTheBankBalanceIsTooLow() {
        when(bankLinks.availableTzs(bank)).thenReturn(new BigDecimal("500000"));
        assertThatThrownBy(() -> service.topUpFloat(move("1000000", "2580"), agentUser.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("bank balance");
        verifyNoInteractions(ledgerService);
    }

    @Test
    void topUp_aBankInAnotherNameIsRefused() {
        when(bankLinks.requireUsable(bank)).thenThrow(new BadRequestException("The name on this bank account does not match"));
        assertThatThrownBy(() -> service.topUpFloat(move("1000000", "2580"), agentUser.getId())).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(ledgerService);
    }

    @Test
    void topUp_aSuspendedAgentCannotTopUp() {
        agent.setAgentStatus(AgentStatus.SUSPENDED);
        assertThatThrownBy(() -> service.topUpFloat(move("1000000", "2580"), agentUser.getId())).isInstanceOf(BadRequestException.class).hasMessageContaining("not active");
        verifyNoInteractions(ledgerService);
    }

    // ------------------------------------------------------------------ send float / commission to the bank
    @Test
    void floatToBank_takesFromFloatPaysOutOfEscrowWithNoFee() {
        BankMoveResponse response = service.floatToBank(move("100000", "2580"), agentUser.getId());

        assertThat(floatWallet.getBalance()).isEqualByComparingTo("200000.00");
        assertThat(escrow.getBalance()).isEqualByComparingTo("1900000.00");
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.fee()).isEqualByComparingTo("0");

        @SuppressWarnings("unchecked") ArgumentCaptor<List<JournalLineRequest>> lines = ArgumentCaptor.forClass(List.class);
        verify(ledgerService).postJournal(eq(LedgerTransactionType.FLOAT_BANK_WITHDRAW), any(), eq(agentUser), lines.capture());
        assertThat(lines.getValue()).containsExactlyInAnyOrder(
                new JournalLineRequest(floatWallet.getId(), EntryType.DEBIT, new BigDecimal("100000")),
                new JournalLineRequest(escrow.getId(), EntryType.CREDIT, new BigDecimal("100000")));
        verify(notifier).notify(eq(NotificationKey.A7), any(), any());
    }

    @Test
    void floatToBank_cannotSendMoreThanTheFloat() {
        assertThatThrownBy(() -> service.floatToBank(move("400000", "2580"), agentUser.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Not enough in your float");
        assertThat(floatWallet.getBalance()).isEqualByComparingTo("300000.00");
        verifyNoInteractions(ledgerService);
    }

    @Test
    void floatToBank_withAWrongPinMovesNothing() {
        assertThatThrownBy(() -> service.floatToBank(move("100000", "1111"), agentUser.getId())).isInstanceOf(BadRequestException.class).hasMessageContaining("Wrong PIN");
        assertThat(floatWallet.getBalance()).isEqualByComparingTo("300000.00");
        verify(ledgerService, never()).postJournal(any(), any(), any(), any());
    }

    @Test
    void commissionToBank_paysTheCommissionOutOfEscrow() {
        BankMoveResponse response = service.commissionToBank(move("10000", "2580"), agentUser.getId());

        assertThat(commissionWallet.getBalance()).isEqualByComparingTo("2000.00");
        assertThat(escrow.getBalance()).isEqualByComparingTo("1990000.00");
        assertThat(response.status()).isEqualTo("PENDING");
        verify(ledgerService).postJournal(eq(LedgerTransactionType.COMMISSION_BANK_WITHDRAW), any(), eq(agentUser), any());
        verify(notifier).notify(eq(NotificationKey.A8), any(), any());
    }

    @Test
    void commissionToBank_cannotSendMoreThanTheCommissionWallet() {
        assertThatThrownBy(() -> service.commissionToBank(move("20000", "2580"), agentUser.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("commission wallet");
        verifyNoInteractions(ledgerService);
    }
}
