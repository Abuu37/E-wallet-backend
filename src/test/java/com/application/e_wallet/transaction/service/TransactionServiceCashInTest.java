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
import com.application.e_wallet.notification.NotificationKey;
import com.application.e_wallet.notification.Notifier;
import com.application.e_wallet.tariff.entity.TariffEntity;
import com.application.e_wallet.tariff.service.TariffService;
import com.application.e_wallet.transaction.dto.CashInRequest;
import com.application.e_wallet.transaction.dto.CashInResponse;
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
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The agent's cash-in: the customer's account number and phone must match, and an empty pool never blocks it. */
@ExtendWith(MockitoExtension.class)
class TransactionServiceCashInTest {

    @Mock private AgentRepository agentRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private TariffService tariffService;
    @Mock private LedgerService ledgerService;
    @Mock private PhoneNumberNormalizer phoneNumberNormalizer;
    @Mock private PasswordEncoder pinEncoder;
    @Mock private LimitService limitService;
    @Mock private CommissionService commissionService;
    @Mock private Notifier notifier;
    @InjectMocks private TransactionService service;

    private UserEntity agentUser, customerUser;
    private AgentEntity agent;
    private CustomerEntity customer;
    private WalletEntity agentFloat, agentCommission, pool, customerWallet;

    private static WalletEntity wallet(UserEntity owner, WalletType type, String number, String balance) {
        return WalletEntity.builder().id(UUID.randomUUID()).user(owner).walletType(type).accountNumber(number)
                .balance(new BigDecimal(balance)).currency("TZS").status(WalletStatus.ACTIVE).build();
    }

    private CashInRequest request(String account, String phone, String amount, String pin) {
        return new CashInRequest("AF-10234", account, new BigDecimal(amount), phone, pin);
    }

    @BeforeEach
    void setUp() {
        agentUser = UserEntity.builder().id(UUID.randomUUID()).pinHash("agent-hash").build();
        customerUser = UserEntity.builder().id(UUID.randomUUID()).phone("+255712345678").build();
        agent = AgentEntity.builder().id(UUID.randomUUID()).user(agentUser).agentNumber("AG-10234").businessName("Mrema Shop").agentStatus(AgentStatus.ACTIVE).build();
        customer = CustomerEntity.builder().user(customerUser).firstName("Amina").lastName("Said").phoneNumber("+255712345678")
                .status(CustomerStatus.ACTIVE).kycStatus(KycStatus.NOT_STARTED).build();

        agentFloat = wallet(agentUser, WalletType.AGENT_FLOAT, "AF-10234", "250000");
        agentCommission = wallet(agentUser, WalletType.AGENT_COMMISSION, "AC-10234", "0");
        pool = wallet(null, WalletType.COMMISSION, "POOL", "10000");
        customerWallet = wallet(customerUser, WalletType.CUSTOMER_MAIN, "WP-0712-345678", "40000");

        lenient().when(agentRepository.findByUserId(agentUser.getId())).thenReturn(Optional.of(agent));
        lenient().when(walletRepository.findByUserIdAndWalletTypeForUpdate(agentUser.getId(), WalletType.AGENT_FLOAT)).thenReturn(Optional.of(agentFloat));
        lenient().when(walletRepository.findByUserIdAndWalletTypeForUpdate(agentUser.getId(), WalletType.AGENT_COMMISSION)).thenReturn(Optional.of(agentCommission));
        lenient().when(walletRepository.findByWalletTypeForUpdate(WalletType.COMMISSION)).thenReturn(Optional.of(pool));
        lenient().when(walletRepository.findByAccountNumberForUpdate("WP-0712-345678")).thenReturn(Optional.of(customerWallet));
        lenient().when(customerRepository.findByUserId(customerUser.getId())).thenReturn(Optional.of(customer));
        lenient().when(phoneNumberNormalizer.normalize("0712345678")).thenReturn("+255712345678");
        lenient().when(pinEncoder.matches("2580", "agent-hash")).thenReturn(true);
        lenient().when(tariffService.findApplicableTariff(any(), any()))
                .thenReturn(TariffEntity.builder().fee(BigDecimal.ZERO).agentCommission(new BigDecimal("200")).build());
        lenient().when(commissionService.poolCanPay(any(), any())).thenReturn(true);
        lenient().when(transactionRepository.existsByReferenceNo(any())).thenReturn(false);
        lenient().when(transactionRepository.save(any(TransactionEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void cashInMovesFloatToTheCustomerAndPaysCommission() {
        CashInResponse response = service.cashIn(request("WP-0712-345678", "0712345678", "50000", "2580"), agentUser.getId());

        assertThat(agentFloat.getBalance()).isEqualByComparingTo("200000");
        assertThat(customerWallet.getBalance()).isEqualByComparingTo("90000");
        assertThat(pool.getBalance()).isEqualByComparingTo("9800");
        assertThat(agentCommission.getBalance()).isEqualByComparingTo("200");
        verify(ledgerService).postJournal(eq(LedgerTransactionType.CASH_IN), any(), eq(agentUser), argThat((List<JournalLineRequest> lines) ->
                lines.stream().anyMatch(l -> l.walletId().equals(agentFloat.getId()) && l.entryType() == EntryType.DEBIT)
                && lines.stream().anyMatch(l -> l.walletId().equals(customerWallet.getId()) && l.entryType() == EntryType.CREDIT)
                && lines.size() == 4));
        assertThat(response.transactionReference()).startsWith("TXN-");
    }

    @Test
    void cashInTellsTheCustomerAndTheAgent() {
        service.cashIn(request("WP-0712-345678", "0712345678", "50000", "2580"), agentUser.getId());

        verify(notifier).notify(eq(NotificationKey.C2), any(), argThat(p -> "50,000".equals(p.get("amount")) && "Mrema Shop".equals(p.get("agent")) && "90,000".equals(p.get("balance"))));
        verify(notifier).notify(eq(NotificationKey.A1), any(), argThat(p -> "Amina Said".equals(p.get("name")) && "200".equals(p.get("commission")) && "200,000".equals(p.get("float"))));
    }

    @Test
    void anEmptyCommissionPoolNeverBlocksACashIn() {
        when(commissionService.poolCanPay(any(), any())).thenReturn(false);

        service.cashIn(request("WP-0712-345678", "0712345678", "50000", "2580"), agentUser.getId());

        assertThat(customerWallet.getBalance()).isEqualByComparingTo("90000");
        assertThat(pool.getBalance()).isEqualByComparingTo("10000");
        verify(ledgerService).postJournal(any(), any(), any(), argThat((List<JournalLineRequest> lines) -> lines.size() == 2));
        verify(commissionService).recordOwed(eq(agent), any(TransactionEntity.class), eq(new BigDecimal("200")));
    }

    @Test
    void thePhoneMustBelongToTheAccountNumber() {
        when(phoneNumberNormalizer.normalize("0700000000")).thenReturn("+255700000000");

        assertThatThrownBy(() -> service.cashIn(request("WP-0712-345678", "0700000000", "50000", "2580"), agentUser.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("does not match");

        verifyNoInteractions(ledgerService, notifier);
        assertThat(customerWallet.getBalance()).isEqualByComparingTo("40000");
    }

    @Test
    void aWrongAgentPinMovesNothing() {
        assertThatThrownBy(() -> service.cashIn(request("WP-0712-345678", "0712345678", "50000", "0000"), agentUser.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Invalid agent PIN");

        verifyNoInteractions(ledgerService);
    }

    @Test
    void cashInNeedsEnoughAgentFloat() {
        agentFloat.setBalance(new BigDecimal("40000"));

        assertThatThrownBy(() -> service.cashIn(request("WP-0712-345678", "0712345678", "50000", "2580"), agentUser.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Insufficient agent float");

        verifyNoInteractions(ledgerService);
        assertThat(customerWallet.getBalance()).isEqualByComparingTo("40000");
    }

    @Test
    void cashInOverTheCustomersMaxBalanceIsRefused() {
        doThrow(new BadRequestException("A wallet can hold at most 1,000,000 TZS.")).when(limitService)
                .checkReceive(any(), eq(KycStatus.NOT_STARTED), any());

        assertThatThrownBy(() -> service.cashIn(request("WP-0712-345678", "0712345678", "50000", "2580"), agentUser.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("hold at most");

        verifyNoInteractions(ledgerService);
        assertThat(agentFloat.getBalance()).isEqualByComparingTo("250000");
    }

    @Test
    void cashInForASuspendedCustomerIsRefused() {
        customer.setStatus(CustomerStatus.SUSPENDED);

        assertThatThrownBy(() -> service.cashIn(request("WP-0712-345678", "0712345678", "50000", "2580"), agentUser.getId()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("not active");

        verifyNoInteractions(ledgerService);
    }
}
