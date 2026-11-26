package com.application.e_wallet.bank.service;

import com.application.e_wallet.bank.entity.BankPostingEntity;
import com.application.e_wallet.bank.entity.LinkedBankEntity;
import com.application.e_wallet.bank.repository.BankPostingRepository;
import com.application.e_wallet.bank.repository.LinkedBankRepository;
import com.application.e_wallet.common.exception.ProviderUnavailableException;
import com.application.e_wallet.integration.plaid.PlaidBankApi;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Sandbox bank line after commit: completing a withdrawal, retrying, and refunding when the bank refuses. */
@ExtendWith(MockitoExtension.class)
class BankPostingServiceTest {

    @Mock private BankPostingRepository postings;
    @Mock private LinkedBankRepository banks;
    @Mock private TransactionRepository transactions;
    @Mock private WalletRepository wallets;
    @Mock private LedgerService ledgerService;
    @Mock private PlaidBankApi plaid;
    @Mock private BankLinkService bankLinks;
    @Mock private Notifier notifier;

    @InjectMocks private BankPostingService service;

    private UserEntity user;
    private WalletEntity customerWallet, escrow, revenue;
    private LinkedBankEntity bank;
    private TransactionEntity withdrawal, topUp;
    private BankPostingEntity credit, debit;

    @BeforeEach
    void setUp() {
        user = UserEntity.builder().id(UUID.randomUUID()).phone("+255712345678").build();
        customerWallet = WalletEntity.builder().id(UUID.randomUUID()).user(user).walletType(WalletType.CUSTOMER_MAIN).balance(new BigDecimal("49000.00")).currency("TZS").status(WalletStatus.ACTIVE).build();
        escrow = WalletEntity.builder().id(UUID.randomUUID()).walletType(WalletType.ESCROW_BANK).balance(new BigDecimal("450000.00")).currency("TZS").status(WalletStatus.ACTIVE).build();
        revenue = WalletEntity.builder().id(UUID.randomUUID()).walletType(WalletType.REVENUE).balance(new BigDecimal("1000.00")).currency("TZS").status(WalletStatus.ACTIVE).build();
        bank = LinkedBankEntity.builder().id(UUID.randomUUID()).userId(user.getId()).institutionName("First Platypus Bank").accountMask("0000").build();
        withdrawal = TransactionEntity.builder().id(UUID.randomUUID()).referenceNo("BWD-1").senderWallet(customerWallet).receiverWallet(escrow)
                .amount(new BigDecimal("50000")).fee(new BigDecimal("1000")).transactionType("BANK_WITHDRAWAL").status("PENDING").build();
        topUp = TransactionEntity.builder().id(UUID.randomUUID()).referenceNo("BTU-1").senderWallet(escrow).receiverWallet(customerWallet)
                .amount(new BigDecimal("50000")).fee(BigDecimal.ZERO).transactionType("BANK_TOPUP").status("COMPLETED").build();
        credit = BankPostingEntity.builder().id(UUID.randomUUID()).transactionId(withdrawal.getId()).linkedBankId(bank.getId()).direction("CREDIT").usdAmount(new BigDecimal("19.24")).build();
        debit = BankPostingEntity.builder().id(UUID.randomUUID()).transactionId(topUp.getId()).linkedBankId(bank.getId()).direction("DEBIT").usdAmount(new BigDecimal("19.24")).build();

        lenient().when(banks.findById(bank.getId())).thenReturn(Optional.of(bank));
        lenient().when(transactions.findById(withdrawal.getId())).thenReturn(Optional.of(withdrawal));
        lenient().when(transactions.findById(topUp.getId())).thenReturn(Optional.of(topUp));
        lenient().when(postings.lockById(credit.getId())).thenReturn(Optional.of(credit));
        lenient().when(postings.lockById(debit.getId())).thenReturn(Optional.of(debit));
        lenient().when(bankLinks.accessToken(bank)).thenReturn("access-sandbox-token");
        lenient().when(wallets.findByWalletType(WalletType.REVENUE)).thenReturn(Optional.of(revenue));
        lenient().when(wallets.lockAllOrdered(anyCollection())).thenReturn(List.of(customerWallet, escrow, revenue));
    }

    @Test
    void aWithdrawalCompletesWhenTheBankLineIsPosted() {
        service.attempt(credit.getId());

        verify(plaid).postSandboxLine("access-sandbox-token", new BigDecimal("-19.24"), "WalletPesa BWD-1");
        assertThat(withdrawal.getStatus()).isEqualTo("COMPLETED");
        assertThat(credit.getStatus()).isEqualTo("DONE");
        verify(ledgerService, never()).postJournal(any(), any(), any(), any());
    }

    @Test
    void aTopUpPostsADebitLineAndKeepsItsCredit() {
        service.attempt(debit.getId());

        verify(plaid).postSandboxLine("access-sandbox-token", new BigDecimal("19.24"), "WalletPesa BTU-1");
        assertThat(debit.getStatus()).isEqualTo("DONE");
        assertThat(topUp.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void aFailedWithdrawalStaysPendingUntilTheLastAttempt() {
        doThrow(new ProviderUnavailableException("down")).when(plaid).postSandboxLine(any(), any(), any());

        service.attempt(credit.getId());

        assertThat(credit.getStatus()).isEqualTo("PENDING");
        assertThat(credit.getAttempts()).isEqualTo(1);
        assertThat(withdrawal.getStatus()).isEqualTo("PENDING");
        assertThat(customerWallet.getBalance()).isEqualByComparingTo("49000.00");
    }

    @Test
    void whenTheBankKeepsRefusingTheAmountAndFeeGoBackToTheWallet() {
        credit.setAttempts(BankPostingService.MAX_WITHDRAWAL_ATTEMPTS - 1);
        doThrow(new ProviderUnavailableException("down")).when(plaid).postSandboxLine(any(), any(), any());

        service.attempt(credit.getId());

        assertThat(credit.getStatus()).isEqualTo("FAILED");
        assertThat(withdrawal.getStatus()).isEqualTo("FAILED");
        assertThat(customerWallet.getBalance()).isEqualByComparingTo("100000.00");
        assertThat(escrow.getBalance()).isEqualByComparingTo("500000.00");
        assertThat(revenue.getBalance()).isEqualByComparingTo("0.00");

        @SuppressWarnings("unchecked") ArgumentCaptor<List<JournalLineRequest>> lines = ArgumentCaptor.forClass(List.class);
        verify(ledgerService).postJournal(eq(LedgerTransactionType.BANK_WITHDRAW_REVERSAL), any(), eq(user), lines.capture());
        assertThat(lines.getValue()).containsExactlyInAnyOrder(
                new JournalLineRequest(customerWallet.getId(), EntryType.CREDIT, new BigDecimal("51000")),
                new JournalLineRequest(escrow.getId(), EntryType.DEBIT, new BigDecimal("50000")),
                new JournalLineRequest(revenue.getId(), EntryType.DEBIT, new BigDecimal("1000")));
        verify(notifier).notify(eq(NotificationKey.C7), any(), any());
    }

    @Test
    void aFailedTopUpPostingIsRetriedAndNeverUndoesTheCredit() {
        doThrow(new ProviderUnavailableException("down")).when(plaid).postSandboxLine(any(), any(), any());

        service.attempt(debit.getId());

        assertThat(debit.getStatus()).isEqualTo("PENDING");
        assertThat(customerWallet.getBalance()).isEqualByComparingTo("49000.00");
        verify(ledgerService, never()).postJournal(any(), any(), any(), any());
    }

    @Test
    void aPostingThatIsAlreadyDoneIsNotPostedTwice() {
        credit.setStatus("DONE");
        service.attempt(credit.getId());
        verify(plaid, never()).postSandboxLine(any(), any(), any());
    }

    @Test
    void aFailedAgentFloatPayoutIsRefundedWithNoFeeAndTheAgentIsTold() {
        WalletEntity agentFloat = WalletEntity.builder().id(UUID.randomUUID()).user(user).walletType(WalletType.AGENT_FLOAT).balance(new BigDecimal("200000.00")).currency("TZS").status(WalletStatus.ACTIVE).build();
        TransactionEntity payout = TransactionEntity.builder().id(UUID.randomUUID()).referenceNo("FBW-1").senderWallet(agentFloat).receiverWallet(escrow)
                .amount(new BigDecimal("100000")).fee(BigDecimal.ZERO).transactionType("FLOAT_BANK_WITHDRAWAL").status("PENDING").build();
        BankPostingEntity posting = BankPostingEntity.builder().id(UUID.randomUUID()).transactionId(payout.getId()).linkedBankId(bank.getId()).direction("CREDIT")
                .usdAmount(new BigDecimal("38.47")).attempts(BankPostingService.MAX_WITHDRAWAL_ATTEMPTS - 1).build();
        when(transactions.findById(payout.getId())).thenReturn(Optional.of(payout));
        when(postings.lockById(posting.getId())).thenReturn(Optional.of(posting));
        when(wallets.lockAllOrdered(anyCollection())).thenReturn(List.of(agentFloat, escrow));
        doThrow(new ProviderUnavailableException("down")).when(plaid).postSandboxLine(any(), any(), any());

        service.attempt(posting.getId());

        assertThat(payout.getStatus()).isEqualTo("FAILED");
        assertThat(agentFloat.getBalance()).isEqualByComparingTo("300000.00");
        assertThat(escrow.getBalance()).isEqualByComparingTo("550000.00");
        assertThat(revenue.getBalance()).isEqualByComparingTo("1000.00");
        verify(notifier).notify(eq(NotificationKey.A10), any(), any());
        verify(wallets, never()).findByWalletType(WalletType.REVENUE);
    }
}
