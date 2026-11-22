package com.application.e_wallet.transaction.service;

import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.ledger.service.LedgerService;
import com.application.e_wallet.tariff.service.TariffService;
import com.application.e_wallet.transaction.dto.TransactionDetailResponse;
import com.application.e_wallet.transaction.dto.TransactionListResponse;
import com.application.e_wallet.transaction.entity.TransactionEntity;
import com.application.e_wallet.transaction.repository.TransactionRepository;
import com.application.e_wallet.common.validation.PhoneNumberNormalizer;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock private AgentRepository agentRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private TariffService tariffService;
    @Mock private LedgerService ledgerService;
    @Mock private PhoneNumberNormalizer phoneNumberNormalizer;
    @Mock private PasswordEncoder pinEncoder;
    @InjectMocks private TransactionService transactionService;

    private UUID customerUserId;
    private WalletEntity customerWallet;
    private WalletEntity counterpartyWallet;

    @BeforeEach
    void setUp() {
        customerUserId = UUID.randomUUID();
        UserEntity customerUser = UserEntity.builder().id(customerUserId).build();

        customerWallet = WalletEntity.builder()
                .id(UUID.randomUUID()).accountNumber("033473180342").accountName("Ibrahim Mohammed").build();
        counterpartyWallet = WalletEntity.builder()
                .id(UUID.randomUUID()).accountNumber("COMPANY-CLEARING-ACCOUNT").accountName("Payment Gateway Clearing").build();

        lenient().when(customerRepository.findByUserId(customerUserId))
                .thenReturn(Optional.of(CustomerEntity.builder().id(UUID.randomUUID()).user(customerUser).build()));
        lenient().when(walletRepository.findByUserIdAndWalletType(customerUserId, WalletType.CUSTOMER_MAIN))
                .thenReturn(Optional.of(customerWallet));
        lenient().when(agentRepository.findByUserId(customerUserId)).thenReturn(Optional.empty());
    }

    @Test
    void listTransactionsUsesFindAllWithWalletsWhenCallerCanViewAll() {
        Pageable pageable = PageRequest.of(0, 20);
        TransactionEntity transaction = transaction("TXN-1", customerWallet, counterpartyWallet, "5000.00");
        when(transactionRepository.findAllWithWallets(pageable))
                .thenReturn(new PageImpl<>(List.of(transaction), pageable, 1));

        TransactionListResponse response = transactionService.listTransactions(customerUserId, true, pageable);

        assertThat(response.transactions()).hasSize(1);
        assertThat(response.transactions().get(0).reference()).isEqualTo("TXN-1");
        assertThat(response.totalElements()).isEqualTo(1);
        org.mockito.Mockito.verifyNoInteractions(customerRepository, agentRepository);
    }

    @Test
    void listTransactionsScopesToCustomersOwnWalletWhenCallerCannotViewAll() {
        Pageable pageable = PageRequest.of(0, 20);
        TransactionEntity transaction = transaction("TXN-2", customerWallet, counterpartyWallet, "1000.00");
        when(transactionRepository.findByWalletIdInWithWallets(eq(List.of(customerWallet.getId())), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(transaction), pageable, 1));

        TransactionListResponse response = transactionService.listTransactions(customerUserId, false, pageable);

        assertThat(response.transactions()).hasSize(1);
        assertThat(response.transactions().get(0).fromAccountNumber()).isEqualTo("033473180342");
        assertThat(response.transactions().get(0).toAccountNumber()).isEqualTo("COMPANY-CLEARING-ACCOUNT");
    }

    @Test
    void listTransactionsScopesToBothAgentWalletsWhenCallerIsAnAgent() {
        UUID agentUserId = UUID.randomUUID();
        WalletEntity floatWallet = WalletEntity.builder().id(UUID.randomUUID()).build();
        WalletEntity commissionWallet = WalletEntity.builder().id(UUID.randomUUID()).build();
        when(customerRepository.findByUserId(agentUserId)).thenReturn(Optional.empty());
        when(agentRepository.findByUserId(agentUserId)).thenReturn(Optional.of(AgentEntity.builder().build()));
        when(walletRepository.findByUserIdAndWalletType(agentUserId, WalletType.AGENT_FLOAT))
                .thenReturn(Optional.of(floatWallet));
        when(walletRepository.findByUserIdAndWalletType(agentUserId, WalletType.AGENT_COMMISSION))
                .thenReturn(Optional.of(commissionWallet));
        Pageable pageable = PageRequest.of(0, 20);
        when(transactionRepository.findByWalletIdInWithWallets(
                eq(List.of(floatWallet.getId(), commissionWallet.getId())), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        transactionService.listTransactions(agentUserId, false, pageable);

        verify(transactionRepository).findByWalletIdInWithWallets(
                List.of(floatWallet.getId(), commissionWallet.getId()), pageable);
    }

    @Test
    void listTransactionsThrowsWhenCallerHasNeitherCustomerNorAgentProfile() {
        UUID strangerUserId = UUID.randomUUID();
        when(customerRepository.findByUserId(strangerUserId)).thenReturn(Optional.empty());
        when(agentRepository.findByUserId(strangerUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.listTransactions(strangerUserId, false, PageRequest.of(0, 20)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void getTransactionDetailReturnsItWhenCallerCanViewAll() {
        TransactionEntity transaction = transaction("TXN-3", customerWallet, counterpartyWallet, "2000.00");
        when(transactionRepository.findByReferenceNo("TXN-3")).thenReturn(Optional.of(transaction));

        TransactionDetailResponse response = transactionService.getTransactionDetail(customerUserId, true, "TXN-3");

        assertThat(response.reference()).isEqualTo("TXN-3");
    }

    @Test
    void getTransactionDetailReturnsItWhenItBelongsToTheCaller() {
        TransactionEntity transaction = transaction("TXN-4", customerWallet, counterpartyWallet, "2000.00");
        when(transactionRepository.findByReferenceNo("TXN-4")).thenReturn(Optional.of(transaction));

        TransactionDetailResponse response = transactionService.getTransactionDetail(customerUserId, false, "TXN-4");

        assertThat(response.reference()).isEqualTo("TXN-4");
    }

    @Test
    void getTransactionDetailHidesTransactionsThatBelongToSomeoneElse() {
        WalletEntity someoneElseWallet = WalletEntity.builder().id(UUID.randomUUID()).build();
        TransactionEntity transaction = transaction("TXN-5", someoneElseWallet, counterpartyWallet, "2000.00");
        when(transactionRepository.findByReferenceNo("TXN-5")).thenReturn(Optional.of(transaction));

        assertThatThrownBy(() -> transactionService.getTransactionDetail(customerUserId, false, "TXN-5"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Transaction not found");
    }

    @Test
    void getTransactionDetailThrowsNotFoundForAnUnknownReference() {
        when(transactionRepository.findByReferenceNo("TXN-UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.getTransactionDetail(customerUserId, true, "TXN-UNKNOWN"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Transaction not found");
    }

    private static TransactionEntity transaction(String reference, WalletEntity sender, WalletEntity receiver, String amount) {
        return TransactionEntity.builder()
                .referenceNo(reference)
                .senderWallet(sender)
                .receiverWallet(receiver)
                .amount(new BigDecimal(amount))
                .fee(BigDecimal.ZERO)
                .commission(BigDecimal.ZERO)
                .transactionType("CASH_IN")
                .status("COMPLETED")
                .createdAt(OffsetDateTime.now())
                .build();
    }
}
