package com.application.e_wallet.bank.service;

import com.application.e_wallet.bank.dto.BankMoveRequest;
import com.application.e_wallet.bank.dto.BankMoveResponse;
import com.application.e_wallet.bank.entity.BankPostingEntity;
import com.application.e_wallet.bank.entity.LinkedBankEntity;
import com.application.e_wallet.bank.repository.BankPostingRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.customer.entity.CustomerStatus;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.kyc.entity.KycStatus;
import com.application.e_wallet.ledger.dto.JournalLineRequest;
import com.application.e_wallet.ledger.entity.EntryType;
import com.application.e_wallet.ledger.entity.LedgerTransactionType;
import com.application.e_wallet.ledger.service.LedgerService;
import com.application.e_wallet.limits.LimitService;
import com.application.e_wallet.notification.NotificationKey;
import com.application.e_wallet.notification.Notifier;
import com.application.e_wallet.tariff.entity.TariffEntity;
import com.application.e_wallet.tariff.service.TariffService;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Add money from a bank and withdraw to a bank: every rule, with the ledger and balances checked after. */
@ExtendWith(MockitoExtension.class)
class BankMoneyServiceTest {

    @Mock private CustomerRepository customerRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private BankPostingRepository postingRepository;
    @Mock private BankLinkService bankLinks;
    @Mock private BankPostingService postingService;
    @Mock private TariffService tariffService;
    @Mock private LedgerService ledgerService;
    @Mock private LimitService limitService;
    @Mock private PasswordEncoder pinEncoder;
    @Mock private Notifier notifier;

    @InjectMocks private BankMoneyService service;

    private UserEntity aminaUser;
    private CustomerEntity amina;
    private WalletEntity aminaWallet, escrow, revenue;
    private LinkedBankEntity bank;

    private static WalletEntity wallet(UserEntity owner, WalletType type, String balance) {
        return WalletEntity.builder().id(UUID.randomUUID()).user(owner).walletType(type).accountNumber("WP-" + type)
                .balance(new BigDecimal(balance)).currency("TZS").status(WalletStatus.ACTIVE).build();
    }

    @BeforeEach
    void setUp() {
        aminaUser = UserEntity.builder().id(UUID.randomUUID()).phone("+255712345678").pinHash("amina-hash").build();
        amina = CustomerEntity.builder().user(aminaUser).firstName("Amina").lastName("Said").phoneNumber("+255712345678")
                .status(CustomerStatus.ACTIVE).kycStatus(KycStatus.NOT_STARTED).build();
        aminaWallet = wallet(aminaUser, WalletType.CUSTOMER_MAIN, "100000.00");
        escrow = wallet(null, WalletType.ESCROW_BANK, "500000.00");
        revenue = wallet(null, WalletType.REVENUE, "0.00");
        bank = LinkedBankEntity.builder().id(UUID.randomUUID()).userId(aminaUser.getId()).institutionName("First Platypus Bank")
                .accountMask("0000").nameMatched(true).build();

        lenient().when(customerRepository.findByUserId(aminaUser.getId())).thenReturn(Optional.of(amina));
        lenient().when(walletRepository.findByUserIdAndWalletType(aminaUser.getId(), WalletType.CUSTOMER_MAIN)).thenReturn(Optional.of(aminaWallet));
        lenient().when(walletRepository.findByWalletType(WalletType.ESCROW_BANK)).thenReturn(Optional.of(escrow));
        lenient().when(walletRepository.findByWalletType(WalletType.REVENUE)).thenReturn(Optional.of(revenue));
        lenient().when(walletRepository.lockAllOrdered(anyCollection())).thenReturn(List.of(aminaWallet, escrow, revenue));
        lenient().when(bankLinks.ownBank(aminaUser.getId(), bank.getId())).thenReturn(bank);
        lenient().when(bankLinks.requireUsable(bank)).thenReturn(bank);
        lenient().when(bankLinks.nameCheck(bank)).thenReturn(BankLinkService.MATCHED);
        lenient().when(bankLinks.availableTzs(bank)).thenReturn(new BigDecimal("260000"));
        lenient().when(bankLinks.toBankCurrency(any())).thenReturn(new BigDecimal("19.24"));
        lenient().when(tariffService.findApplicableTariff("BANK_WITHDRAWAL", new BigDecimal("50000")))
                .thenReturn(TariffEntity.builder().fee(new BigDecimal("1000")).agentCommission(BigDecimal.ZERO).build());
        lenient().when(pinEncoder.matches("2580", "amina-hash")).thenReturn(true);
        lenient().when(transactionRepository.existsByReferenceNo(any())).thenReturn(false);
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

    // ------------------------------------------------------------------ add money from my bank
    @Test
    void topUp_raisesEscrowAndTheWalletTogetherAndPostsABalancedJournal() {
        BankMoveResponse response = service.topUp(move("50000", "2580"), aminaUser.getId());

        assertThat(aminaWallet.getBalance()).isEqualByComparingTo("150000.00");
        assertThat(escrow.getBalance()).isEqualByComparingTo("550000.00");
        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.fee()).isEqualByComparingTo("0");
        assertThat(response.balanceAfter()).isEqualByComparingTo("150000.00");

        @SuppressWarnings("unchecked") ArgumentCaptor<List<JournalLineRequest>> lines = ArgumentCaptor.forClass(List.class);
        verify(ledgerService).postJournal(eq(LedgerTransactionType.BANK_TOPUP), any(), eq(aminaUser), lines.capture());
        assertThat(lines.getValue()).containsExactlyInAnyOrder(
                new JournalLineRequest(escrow.getId(), EntryType.DEBIT, new BigDecimal("50000")),
                new JournalLineRequest(aminaWallet.getId(), EntryType.CREDIT, new BigDecimal("50000")));

        ArgumentCaptor<TransactionEntity> saved = ArgumentCaptor.forClass(TransactionEntity.class);
        verify(transactionRepository).save(saved.capture());
        assertThat(saved.getValue().getTransactionType()).isEqualTo("BANK_TOPUP");
        assertThat(saved.getValue().getCounterpartyLabel()).isEqualTo("First Platypus Bank ••0000");
        verify(postingService).attemptAfterCommit(any());
        verify(notifier).notify(eq(NotificationKey.C1), any(), any());
    }

    @Test
    void topUp_withAWrongPinMovesNothing() {
        assertThatThrownBy(() -> service.topUp(move("50000", "0000"), aminaUser.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Wrong PIN");
        assertThat(aminaWallet.getBalance()).isEqualByComparingTo("100000.00");
        verifyNoInteractions(ledgerService, notifier, postingService);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void topUp_refusesWhenTheBankBalanceIsTooLow() {
        when(bankLinks.availableTzs(bank)).thenReturn(new BigDecimal("20000"));
        assertThatThrownBy(() -> service.topUp(move("50000", "2580"), aminaUser.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("bank balance");
        verifyNoInteractions(ledgerService, postingService);
    }

    @Test
    void topUp_overTheLimitIsRefused() {
        doThrow(new BadRequestException("This is over your 200,000 TZS per-payment limit.")).when(limitService).checkPerPayment(any(), any());
        assertThatThrownBy(() -> service.topUp(move("50000", "2580"), aminaUser.getId())).isInstanceOf(BadRequestException.class).hasMessageContaining("per-payment");
        assertThat(aminaWallet.getBalance()).isEqualByComparingTo("100000.00");
        verifyNoInteractions(ledgerService);
    }

    @Test
    void topUp_overTheMaxBalanceIsRefused() {
        doThrow(new BadRequestException("A wallet can hold at most 1,000,000 TZS.")).when(limitService).checkReceive(any(), any(), any());
        assertThatThrownBy(() -> service.topUp(move("50000", "2580"), aminaUser.getId())).isInstanceOf(BadRequestException.class).hasMessageContaining("hold at most");
        assertThat(escrow.getBalance()).isEqualByComparingTo("500000.00");
    }

    @Test
    void topUp_aBankWhoseNameDoesNotMatchIsRefused() {
        when(bankLinks.requireUsable(bank)).thenThrow(new BadRequestException("The name on this bank account does not match"));
        assertThatThrownBy(() -> service.topUp(move("50000", "2580"), aminaUser.getId())).isInstanceOf(BadRequestException.class).hasMessageContaining("does not match");
        verifyNoInteractions(ledgerService, postingService);
    }

    // ------------------------------------------------------------------ withdraw to my bank
    @Test
    void withdraw_takesAmountAndFeeAndPaysOutOfEscrowAsPending() {
        BankMoveResponse response = service.withdraw(move("50000", "2580"), aminaUser.getId());

        assertThat(aminaWallet.getBalance()).isEqualByComparingTo("49000.00");
        assertThat(escrow.getBalance()).isEqualByComparingTo("450000.00");
        assertThat(revenue.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.total()).isEqualByComparingTo("51000");

        @SuppressWarnings("unchecked") ArgumentCaptor<List<JournalLineRequest>> lines = ArgumentCaptor.forClass(List.class);
        verify(ledgerService).postJournal(eq(LedgerTransactionType.BANK_WITHDRAW), any(), eq(aminaUser), lines.capture());
        assertThat(lines.getValue()).containsExactlyInAnyOrder(
                new JournalLineRequest(aminaWallet.getId(), EntryType.DEBIT, new BigDecimal("51000")),
                new JournalLineRequest(escrow.getId(), EntryType.CREDIT, new BigDecimal("50000")),
                new JournalLineRequest(revenue.getId(), EntryType.CREDIT, new BigDecimal("1000")));
        verify(postingService).attemptAfterCommit(any());
        verify(notifier).notify(eq(NotificationKey.C6), any(), any());
    }

    @Test
    void withdraw_needsTheAmountPlusTheFee() {
        aminaWallet.setBalance(new BigDecimal("50500.00"));
        assertThatThrownBy(() -> service.withdraw(move("50000", "2580"), aminaUser.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Not enough balance");
        assertThat(aminaWallet.getBalance()).isEqualByComparingTo("50500.00");
        verifyNoInteractions(ledgerService, postingService);
    }

    @Test
    void withdraw_withAWrongPinMovesNothing() {
        assertThatThrownBy(() -> service.withdraw(move("50000", "0000"), aminaUser.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Wrong PIN");
        assertThat(aminaWallet.getBalance()).isEqualByComparingTo("100000.00");
        verifyNoInteractions(ledgerService, notifier);
    }

    @Test
    void withdraw_overTheDailyLimitIsRefused() {
        doThrow(new BadRequestException("This is over your 500,000 TZS daily limit.")).when(limitService).checkSend(any(), any(), any());
        assertThatThrownBy(() -> service.withdraw(move("50000", "2580"), aminaUser.getId())).isInstanceOf(BadRequestException.class).hasMessageContaining("daily limit");
        assertThat(aminaWallet.getBalance()).isEqualByComparingTo("100000.00");
        verifyNoInteractions(ledgerService);
    }
}
