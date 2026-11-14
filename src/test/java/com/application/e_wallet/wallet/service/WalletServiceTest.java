package com.application.e_wallet.wallet.service;

import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.repository.AgentRepository;
import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.wallet.dto.WalletResponse;
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
        assertThat(wallet.getWalletNumber()).isEqualTo("0712345678");
        assertThat(wallet.getAccountNumber()).isEqualTo("0712345678");
        assertThat(wallet.getAccountName()).isEqualTo("Jane Doe");
    }

    @Test
    void getWalletByNumberMapsCustomerMainToCustomerOwnerType() {
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).phone("0712345678").build();
        WalletEntity wallet = WalletEntity.builder()
                .id(UUID.randomUUID())
                .user(user)
                .walletType(WalletType.CUSTOMER_MAIN)
                .walletNumber("0712345678")
                .accountNumber("0712345678")
                .currency("TZS")
                .balance(new BigDecimal("500.00"))
                .status(WalletStatus.ACTIVE)
                .build();
        when(walletRepository.findByWalletNumber("0712345678")).thenReturn(Optional.of(wallet));

        WalletResponse response = walletService.getWalletByNumber("0712345678");

        assertThat(response.ownerType()).isEqualTo("CUSTOMER");
        assertThat(response.balance()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(response.accountNumber()).isEqualTo("0712345678");
    }

    @Test
    void getMyWalletForAgentReturnsTheFloatWallet() {
        UUID userId = UUID.randomUUID();
        UserEntity user = UserEntity.builder().id(userId).phone("0798765432").build();
        AgentEntity agent = AgentEntity.builder().id(UUID.randomUUID()).user(user).build();

        WalletEntity floatWallet = WalletEntity.builder()
                .id(UUID.randomUUID())
                .user(user)
                .walletType(WalletType.AGENT_FLOAT)
                .walletNumber("FLT-123")
                .accountNumber("FLT-123")
                .currency("TZS")
                .balance(new BigDecimal("1000.00"))
                .status(WalletStatus.ACTIVE)
                .build();

        when(customerRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(agentRepository.findByUserId(userId)).thenReturn(Optional.of(agent));
        when(walletRepository.findByUserIdAndWalletType(userId, WalletType.AGENT_FLOAT))
                .thenReturn(Optional.of(floatWallet));

        WalletResponse response = walletService.getMyWallet(userId);

        assertThat(response.ownerType()).isEqualTo("AGENT");
        assertThat(response.walletNumber()).isEqualTo("FLT-123");
        assertThat(response.balance()).isEqualByComparingTo(new BigDecimal("1000.00"));
    }
}
