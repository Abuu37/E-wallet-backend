package com.application.e_wallet.wallet.service;

import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.ledger.repository.TransactionJournalLineRepository;
import com.application.e_wallet.role.entity.RoleEntity;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.wallet.dto.WalletDetailResponse;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.entity.WalletStatus;
import com.application.e_wallet.wallet.entity.WalletType;
import com.application.e_wallet.wallet.repository.WalletRepository;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private TransactionJournalLineRepository transactionJournalLineRepository;

    @InjectMocks
    private WalletService walletService;

    @Test
    void createWalletCreatesOneCustomerMainWalletWithZeroBalance() {
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).phone("0712345678").build();
        CustomerEntity customer = CustomerEntity.builder()
                .id(UUID.randomUUID())
                .user(user)
                .firstName("Jane")
                .lastName("Doe")
                .build();
        when(walletRepository.existsByUserIdAndWalletType(user.getId(), WalletType.CUSTOMER_MAIN)).thenReturn(false);
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WalletEntity wallet = walletService.createWallet(customer);

        assertThat(wallet.getWalletType()).isEqualTo(WalletType.CUSTOMER_MAIN);
        assertThat(wallet.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        // "03" lead + 10 digits = 12 digits, per the agreed customer account format.
        assertThat(wallet.getAccountNumber()).matches("03\\d{10}");
        assertThat(wallet.getAccountName()).isEqualTo("Jane Doe");
    }

    @Test
    void getWalletByAccountNumberMapsCustomerMainToCustomerOwnerType() {
        UUID userId = UUID.randomUUID();
        RoleEntity role = RoleEntity.builder().id(UUID.randomUUID()).name("CUSTOMER").build();
        UserEntity user = UserEntity.builder().id(userId).phone("0712345678").role(role).build();
        WalletEntity wallet = WalletEntity.builder()
                .id(UUID.randomUUID())
                .user(user)
                .walletType(WalletType.CUSTOMER_MAIN)
                .accountNumber("0112345678AB")
                .currency("TZS")
                .balance(new BigDecimal("500.00"))
                .status(WalletStatus.ACTIVE)
                .build();
        CustomerEntity customer = CustomerEntity.builder()
                .id(UUID.randomUUID())
                .user(user)
                .firstName("Jane")
                .lastName("Doe")
                .build();
        when(walletRepository.findByAccountNumber("0112345678AB")).thenReturn(Optional.of(wallet));
        when(customerRepository.findByUserId(userId)).thenReturn(Optional.of(customer));
        when(transactionJournalLineRepository.findTop5ByWalletIdInOrderByJournal_CreatedAtDesc(List.of(wallet.getId())))
                .thenReturn(List.of());

        WalletDetailResponse response = walletService.getWalletByAccountNumber("0112345678AB");

        assertThat(response.owner().name()).isEqualTo("Jane Doe");
        assertThat(response.owner().role()).isEqualTo("CUSTOMER");
        assertThat(response.balance()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(response.accountNumber()).isEqualTo("0112345678AB");
    }

    @Test
    void getMyWalletForAgentReturnsTheFloatWallet() {
        UUID userId = UUID.randomUUID();
        RoleEntity role = RoleEntity.builder().id(UUID.randomUUID()).name("AGENT").build();
        UserEntity user = UserEntity.builder().id(userId).phone("0798765432").role(role).build();
        AgentEntity agent = AgentEntity.builder()
                .id(UUID.randomUUID())
                .user(user)
                .businessName("Kariakoo Wakala Point")
                .agentNumber("40512")
                .build();

        WalletEntity floatWallet = WalletEntity.builder()
                .id(UUID.randomUUID())
                .user(user)
                .walletType(WalletType.AGENT_FLOAT)
                .accountNumber("019876543210")
                .currency("TZS")
                .balance(new BigDecimal("1000.00"))
                .status(WalletStatus.ACTIVE)
                .build();

        when(customerRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(agentRepository.findByUserId(userId)).thenReturn(Optional.of(agent));
        when(walletRepository.findByUserIdAndWalletType(userId, WalletType.AGENT_FLOAT))
                .thenReturn(Optional.of(floatWallet));
        when(transactionJournalLineRepository.findTop5ByWalletIdInOrderByJournal_CreatedAtDesc(List.of(floatWallet.getId())))
                .thenReturn(List.of());

        WalletDetailResponse response = walletService.getMyWallet(userId);

        assertThat(response.owner().name()).isEqualTo("Kariakoo Wakala Point");
        assertThat(response.owner().role()).isEqualTo("AGENT");
        assertThat(response.accountNumber()).isEqualTo("019876543210");
        assertThat(response.balance()).isEqualByComparingTo(new BigDecimal("1000.00"));
    }
}
