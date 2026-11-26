package com.application.e_wallet.transaction.service;

import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.entity.AgentStatus;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.common.validation.PhoneNumberNormalizer;
import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.customer.entity.CustomerStatus;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.kyc.entity.KycStatus;
import com.application.e_wallet.ledger.dto.JournalLineRequest;
import com.application.e_wallet.ledger.entity.EntryType;
import com.application.e_wallet.ledger.entity.LedgerTransactionType;
import com.application.e_wallet.ledger.service.LedgerService;
import com.application.e_wallet.limits.LimitService;
import com.application.e_wallet.location.entity.DistrictEntity;
import com.application.e_wallet.location.entity.RegionEntity;
import com.application.e_wallet.notification.NotificationKey;
import com.application.e_wallet.notification.Notifier;
import com.application.e_wallet.tariff.entity.TariffEntity;
import com.application.e_wallet.tariff.service.TariffService;
import com.application.e_wallet.transaction.dto.TransferRequest;
import com.application.e_wallet.transaction.dto.TransferResponse;
import com.application.e_wallet.transaction.dto.WithdrawRequest;
import com.application.e_wallet.transaction.dto.WithdrawResponse;
import com.application.e_wallet.transaction.entity.TransactionEntity;
import com.application.e_wallet.transaction.repository.TransactionRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.repository.UserRepository;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletStatus;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
import com.application.e_wallet.wallet.service.BeneficiaryService;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Send money and withdraw at an agent: every rule, with the ledger and balances checked after. */
@ExtendWith(MockitoExtension.class)
class CustomerMoneyServiceTest {

    @Mock private CustomerRepository customerRepository;
    @Mock private UserRepository userRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private TariffService tariffService;
    @Mock private LedgerService ledgerService;
    @Mock private LimitService limitService;
    @Mock private CommissionService commissionService;
    @Mock private BeneficiaryService beneficiaryService;
    @Mock private PasswordEncoder pinEncoder;
    @Mock private PhoneNumberNormalizer phoneNumberNormalizer;
    @Mock private Notifier notifier;

    @InjectMocks private CustomerMoneyService service;

    private UserEntity aminaUser, jumaUser, agentUser;
    private CustomerEntity amina, juma;
    private AgentEntity agent;
    private WalletEntity aminaWallet, jumaWallet, agentFloat, agentCommission, pool, revenue;

    private static WalletEntity wallet(UserEntity owner, WalletType type, String balance) {
        return WalletEntity.builder().id(UUID.randomUUID()).user(owner).walletType(type).accountNumber("WP-" + type)
                .balance(new BigDecimal(balance)).currency("TZS").status(WalletStatus.ACTIVE).build();
    }

    private static TariffEntity tariff(String fee, String commission) {
        return TariffEntity.builder().fee(new BigDecimal(fee)).agentCommission(new BigDecimal(commission)).build();
    }

    @BeforeEach
    void setUp() {
        aminaUser = UserEntity.builder().id(UUID.randomUUID()).phone("+255712345678").pinHash("amina-hash").build();
        jumaUser = UserEntity.builder().id(UUID.randomUUID()).phone("+255754123210").pinHash("juma-hash").build();
        agentUser = UserEntity.builder().id(UUID.randomUUID()).phone("+255744000111").pinHash("agent-hash").build();

        amina = CustomerEntity.builder().user(aminaUser).firstName("Amina").lastName("Said").phoneNumber("+255712345678")
                .status(CustomerStatus.ACTIVE).kycStatus(KycStatus.NOT_STARTED).build();
        juma = CustomerEntity.builder().user(jumaUser).firstName("Juma").lastName("Ali").phoneNumber("+255754123210")
                .status(CustomerStatus.ACTIVE).kycStatus(KycStatus.NOT_STARTED).build();
        agent = AgentEntity.builder().id(UUID.randomUUID()).user(agentUser).agentNumber("AG-10234").tillNumber("220341")
                .businessName("Mrema Shop").agentStatus(AgentStatus.ACTIVE)
                .region(RegionEntity.builder().name("Dar es Salaam").build()).district(DistrictEntity.builder().name("Kinondoni").build()).build();

        aminaWallet = wallet(aminaUser, WalletType.CUSTOMER_MAIN, "100000.00");
        jumaWallet = wallet(jumaUser, WalletType.CUSTOMER_MAIN, "5000.00");
        agentFloat = wallet(agentUser, WalletType.AGENT_FLOAT, "250000.00");
        agentCommission = wallet(agentUser, WalletType.AGENT_COMMISSION, "0.00");
        pool = wallet(null, WalletType.COMMISSION, "10000.00");
        revenue = wallet(null, WalletType.REVENUE, "0.00");

        lenient().when(customerRepository.findByUserId(aminaUser.getId())).thenReturn(Optional.of(amina));
        lenient().when(customerRepository.findByUserId(jumaUser.getId())).thenReturn(Optional.of(juma));
        lenient().when(phoneNumberNormalizer.normalize("0754123210")).thenReturn("+255754123210");
        lenient().when(userRepository.findByPhone("+255754123210")).thenReturn(Optional.of(jumaUser));
        lenient().when(agentRepository.findByTillNumber("220341")).thenReturn(Optional.of(agent));

        lenient().when(walletRepository.findByUserIdAndWalletType(aminaUser.getId(), WalletType.CUSTOMER_MAIN)).thenReturn(Optional.of(aminaWallet));
        lenient().when(walletRepository.findByUserIdAndWalletType(jumaUser.getId(), WalletType.CUSTOMER_MAIN)).thenReturn(Optional.of(jumaWallet));
        lenient().when(walletRepository.findByUserIdAndWalletType(agentUser.getId(), WalletType.AGENT_FLOAT)).thenReturn(Optional.of(agentFloat));
        lenient().when(walletRepository.findByUserIdAndWalletType(agentUser.getId(), WalletType.AGENT_COMMISSION)).thenReturn(Optional.of(agentCommission));
        lenient().when(walletRepository.findByWalletType(WalletType.COMMISSION)).thenReturn(Optional.of(pool));
        lenient().when(walletRepository.findByWalletType(WalletType.REVENUE)).thenReturn(Optional.of(revenue));
        lenient().when(walletRepository.lockAllOrdered(anyCollection())).thenReturn(
                List.of(aminaWallet, jumaWallet, agentFloat, agentCommission, pool, revenue));

        lenient().when(pinEncoder.matches("2580", "amina-hash")).thenReturn(true);
        lenient().when(transactionRepository.existsByReferenceNo(any())).thenReturn(false);
        lenient().when(transactionRepository.save(any(TransactionEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(commissionService.poolCanPay(any(), any())).thenReturn(true);
    }

    private TransferRequest send(String amount, String pin) {
        return new TransferRequest("0754123210", new BigDecimal(amount), pin);
    }

    private WithdrawRequest withdraw(String amount, String pin) {
        return new WithdrawRequest("220 341", new BigDecimal(amount), pin);
    }

    private static boolean journalHas(List<JournalLineRequest> lines, UUID wallet, EntryType type, String amount) {
        return lines.stream().anyMatch(l -> l.walletId().equals(wallet) && l.entryType() == type && l.amount().compareTo(new BigDecimal(amount)) == 0);
    }

    // =================================================================== transfer

    @Test
    void transferMovesAmountAndFeeAndPostsABalancedJournal() {
        when(tariffService.findApplicableTariff("TRANSFER", new BigDecimal("20000"))).thenReturn(tariff("300", "0"));

        TransferResponse response = service.transfer(send("20000", "2580"), aminaUser.getId());

        assertThat(aminaWallet.getBalance()).isEqualByComparingTo("79700");
        assertThat(jumaWallet.getBalance()).isEqualByComparingTo("25000");
        assertThat(revenue.getBalance()).isEqualByComparingTo("300");
        verify(ledgerService).postJournal(eq(LedgerTransactionType.TRANSFER), any(), eq(aminaUser), argThat(lines ->
                journalHas(lines, aminaWallet.getId(), EntryType.DEBIT, "20300")
                && journalHas(lines, jumaWallet.getId(), EntryType.CREDIT, "20000")
                && journalHas(lines, revenue.getId(), EntryType.CREDIT, "300")));
        assertThat(response.total()).isEqualByComparingTo("20300");
        assertThat(response.newBalance()).isEqualByComparingTo("79700");
        assertThat(response.recipientName()).isEqualTo("Juma A.");
        assertThat(response.recipientPhone()).isEqualTo("0754 ••• 210");
    }

    @Test
    void transferLocksAllWalletsInOneOrderedStatement() {
        when(tariffService.findApplicableTariff(any(), any())).thenReturn(tariff("300", "0"));

        service.transfer(send("20000", "2580"), aminaUser.getId());

        verify(walletRepository).lockAllOrdered(argThat(ids -> ids.size() == 3));
    }

    @Test
    void transferTellsBothPeopleAfterwardsAndRemembersTheRecipient() {
        when(tariffService.findApplicableTariff(any(), any())).thenReturn(tariff("300", "0"));

        service.transfer(send("20000", "2580"), aminaUser.getId());

        verify(notifier).notify(eq(NotificationKey.C3), any(), argThat(p -> "20,000".equals(p.get("amount")) && "Juma Ali".equals(p.get("name")) && "79,700".equals(p.get("balance"))));
        verify(notifier).notify(eq(NotificationKey.C4), any(), argThat(p -> "Amina Said".equals(p.get("name")) && "25,000".equals(p.get("balance"))));
        verify(beneficiaryService).remember(aminaUser, jumaWallet);
    }

    @Test
    void transferWithAWrongPinMovesNothing() {
        when(tariffService.findApplicableTariff(any(), any())).thenReturn(tariff("300", "0"));

        assertThatThrownBy(() -> service.transfer(send("20000", "0000"), aminaUser.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Wrong PIN");

        assertThat(aminaWallet.getBalance()).isEqualByComparingTo("100000");
        verifyNoInteractions(ledgerService, notifier);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void transferWithTooLittleBalanceCountsTheFee() {
        when(tariffService.findApplicableTariff(any(), any())).thenReturn(tariff("600", "0"));
        aminaWallet.setBalance(new BigDecimal("50500"));

        assertThatThrownBy(() -> service.transfer(send("50000", "2580"), aminaUser.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Not enough balance").hasMessageContaining("50,600");

        verifyNoInteractions(ledgerService);
        assertThat(aminaWallet.getBalance()).isEqualByComparingTo("50500");
    }

    @Test
    void transferOverTheLimitIsRefusedByTheSharedLimitService() {
        aminaWallet.setBalance(new BigDecimal("1000000.00"));
        when(tariffService.findApplicableTariff(any(), any())).thenReturn(tariff("600", "0"));
        doThrow(new BadRequestException("This is over your 200,000 TZS per-payment limit.")).when(limitService)
                .checkSend(eq(aminaWallet.getId()), eq(KycStatus.NOT_STARTED), any());

        assertThatThrownBy(() -> service.transfer(send("250000", "2580"), aminaUser.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("per-payment limit");

        verifyNoInteractions(ledgerService);
        assertThat(aminaWallet.getBalance()).isEqualByComparingTo("1000000");
    }

    @Test
    void transferIntoAFullWalletIsRefused() {
        when(tariffService.findApplicableTariff(any(), any())).thenReturn(tariff("300", "0"));
        doThrow(new BadRequestException("A wallet can hold at most 1,000,000 TZS.")).when(limitService)
                .checkReceive(any(), eq(KycStatus.NOT_STARTED), any());

        assertThatThrownBy(() -> service.transfer(send("20000", "2580"), aminaUser.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("hold at most");

        verifyNoInteractions(ledgerService);
    }

    @Test
    void transferToANumberNotOnWalletPesaIsRefused() {
        when(phoneNumberNormalizer.normalize("0700000000")).thenReturn("+255700000000");
        when(userRepository.findByPhone("+255700000000")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transfer(new TransferRequest("0700000000", new BigDecimal("20000"), "2580"), aminaUser.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("not on WalletPesa");
    }

    @Test
    void transferToYourselfIsRefused() {
        lenient().when(phoneNumberNormalizer.normalize("0712345678")).thenReturn("+255712345678");
        lenient().when(userRepository.findByPhone("+255712345678")).thenReturn(Optional.of(aminaUser));

        assertThatThrownBy(() -> service.transfer(new TransferRequest("0712345678", new BigDecimal("20000"), "2580"), aminaUser.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("yourself");
    }

    @Test
    void transferToASuspendedCustomerIsRefused() {
        when(tariffService.findApplicableTariff(any(), any())).thenReturn(tariff("300", "0"));
        juma.setStatus(CustomerStatus.SUSPENDED);

        assertThatThrownBy(() -> service.transfer(send("20000", "2580"), aminaUser.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("cannot receive");

        verifyNoInteractions(ledgerService);
    }

    @Test
    void transferRefusesZeroNegativeAndExtraDecimals() {
        assertThatThrownBy(() -> service.transfer(send("0", "2580"), aminaUser.getId())).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.transfer(send("-5", "2580"), aminaUser.getId())).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.transfer(send("100.005", "2580"), aminaUser.getId())).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(ledgerService);
    }

    // =================================================================== withdraw at an agent

    @Test
    void withdrawMovesCashFeeAndCommissionAndReturnsAMatchCode() {
        when(tariffService.findApplicableTariff("CASH_OUT", new BigDecimal("30000"))).thenReturn(tariff("300", "200"));

        WithdrawResponse response = service.withdraw(withdraw("30000", "2580"), aminaUser.getId());

        assertThat(aminaWallet.getBalance()).isEqualByComparingTo("69700");
        assertThat(agentFloat.getBalance()).isEqualByComparingTo("280000");
        assertThat(revenue.getBalance()).isEqualByComparingTo("300");
        assertThat(pool.getBalance()).isEqualByComparingTo("9800");
        assertThat(agentCommission.getBalance()).isEqualByComparingTo("200");
        verify(ledgerService).postJournal(eq(LedgerTransactionType.CASH_OUT), any(), eq(aminaUser), argThat(lines ->
                journalHas(lines, aminaWallet.getId(), EntryType.DEBIT, "30300")
                && journalHas(lines, agentFloat.getId(), EntryType.CREDIT, "30000")
                && journalHas(lines, revenue.getId(), EntryType.CREDIT, "300")
                && journalHas(lines, pool.getId(), EntryType.DEBIT, "200")
                && journalHas(lines, agentCommission.getId(), EntryType.CREDIT, "200")));
        assertThat(response.matchCode()).matches("\\d{4}");
        assertThat(response.total()).isEqualByComparingTo("30300");
        assertThat(response.agent().businessName()).isEqualTo("Mrema Shop");

        ArgumentCaptor<TransactionEntity> saved = ArgumentCaptor.forClass(TransactionEntity.class);
        verify(transactionRepository).save(saved.capture());
        assertThat(saved.getValue().getMatchCode()).isEqualTo(response.matchCode());
    }

    @Test
    void withdrawNotifiesTheCustomerAndTheAgentWithTheSameCode() {
        when(tariffService.findApplicableTariff(any(), any())).thenReturn(tariff("300", "200"));

        WithdrawResponse response = service.withdraw(withdraw("30000", "2580"), aminaUser.getId());

        verify(notifier).notify(eq(NotificationKey.C5), any(), argThat(p -> response.matchCode().equals(p.get("code")) && "220 341".equals(p.get("till"))));
        verify(notifier).notify(eq(NotificationKey.A2), any(), argThat(p -> response.matchCode().equals(p.get("code")) && "Amina Said".equals(p.get("name"))));
    }

    @Test
    void anEmptyCommissionPoolNeverBlocksAWithdrawalTheCommissionIsOwed() {
        when(tariffService.findApplicableTariff(any(), any())).thenReturn(tariff("300", "200"));
        when(commissionService.poolCanPay(any(), any())).thenReturn(false);

        service.withdraw(withdraw("30000", "2580"), aminaUser.getId());

        assertThat(aminaWallet.getBalance()).isEqualByComparingTo("69700");
        assertThat(pool.getBalance()).isEqualByComparingTo("10000");
        assertThat(agentCommission.getBalance()).isEqualByComparingTo("0");
        verify(ledgerService).postJournal(eq(LedgerTransactionType.CASH_OUT), any(), any(), argThat(lines ->
                lines.stream().noneMatch(l -> l.walletId().equals(pool.getId()) || l.walletId().equals(agentCommission.getId()))));
        verify(commissionService).recordOwed(eq(agent), any(TransactionEntity.class), eq(new BigDecimal("200")));
    }

    @Test
    void withdrawWithAWrongPinMovesNothing() {
        when(tariffService.findApplicableTariff(any(), any())).thenReturn(tariff("300", "200"));

        assertThatThrownBy(() -> service.withdraw(withdraw("30000", "9999"), aminaUser.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Wrong PIN");

        assertThat(aminaWallet.getBalance()).isEqualByComparingTo("100000");
        verifyNoInteractions(ledgerService, notifier);
    }

    @Test
    void withdrawWithTooLittleBalanceIsRefused() {
        when(tariffService.findApplicableTariff(any(), any())).thenReturn(tariff("300", "200"));
        aminaWallet.setBalance(new BigDecimal("30200"));

        assertThatThrownBy(() -> service.withdraw(withdraw("30000", "2580"), aminaUser.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Not enough balance");

        verifyNoInteractions(ledgerService);
    }

    @Test
    void withdrawOverTheLimitIsRefused() {
        when(tariffService.findApplicableTariff(any(), any())).thenReturn(tariff("300", "200"));
        doThrow(new BadRequestException("This is over your 500,000 TZS daily limit.")).when(limitService)
                .checkSend(eq(aminaWallet.getId()), any(), any());

        assertThatThrownBy(() -> service.withdraw(withdraw("30000", "2580"), aminaUser.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("daily limit");

        verifyNoInteractions(ledgerService);
    }

    @Test
    void withdrawFromAnUnknownTillIsRefused() {
        when(agentRepository.findByTillNumber("999999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdraw(new WithdrawRequest("999999", new BigDecimal("30000"), "2580"), aminaUser.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("till number");
    }

    @Test
    void withdrawFromASuspendedAgentIsRefused() {
        agent.setAgentStatus(AgentStatus.SUSPENDED);

        assertThatThrownBy(() -> service.withdraw(withdraw("30000", "2580"), aminaUser.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("till number");
    }

    @Test
    void withdrawIsRefusedWhenTheAgentsFloatWalletIsNotActive() {
        when(tariffService.findApplicableTariff(any(), any())).thenReturn(tariff("300", "200"));
        agentFloat.setStatus(WalletStatus.SUSPENDED);

        assertThatThrownBy(() -> service.withdraw(withdraw("30000", "2580"), aminaUser.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("cannot serve");
    }
}
