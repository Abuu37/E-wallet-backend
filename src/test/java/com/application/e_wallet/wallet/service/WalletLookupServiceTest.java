package com.application.e_wallet.wallet.service;

import com.application.e_wallet.common.validation.PhoneNumberNormalizer;
import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.customer.entity.CustomerStatus;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.repository.UserRepository;
import com.application.e_wallet.wallet.dto.LookupResponse;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class WalletLookupServiceTest {

    @Mock private PhoneNumberNormalizer normalizer;
    @Mock private UserRepository userRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private WalletRepository walletRepository;
    @InjectMocks private WalletLookupService service;

    private UserEntity user;
    private CustomerEntity customer;
    private WalletEntity wallet;

    @BeforeEach
    void setUp() {
        user = UserEntity.builder().id(UUID.randomUUID()).phone("+255754123210").build();
        customer = CustomerEntity.builder().user(user).firstName("Amina").lastName("Juma").status(CustomerStatus.ACTIVE).build();
        wallet = WalletEntity.builder().id(UUID.randomUUID()).status(WalletStatus.ACTIVE).build();
        lenient().when(normalizer.normalize("0754123210")).thenReturn("+255754123210");
        lenient().when(userRepository.findByPhone("+255754123210")).thenReturn(Optional.of(user));
        lenient().when(customerRepository.findByUserId(user.getId())).thenReturn(Optional.of(customer));
        lenient().when(walletRepository.findByUserIdAndWalletType(user.getId(), WalletType.CUSTOMER_MAIN)).thenReturn(Optional.of(wallet));
    }

    @Test
    void aKnownCustomerShowsAMaskedNameAndCanReceive() {
        LookupResponse response = service.lookup("0754123210");

        assertThat(response.found()).isTrue();
        assertThat(response.name()).isEqualTo("Amina J.");
        assertThat(response.canReceive()).isTrue();
    }

    @Test
    void theFullLastNameIsNeverShown() {
        assertThat(service.lookup("0754123210").name()).doesNotContain("Juma");
    }

    @Test
    void aSuspendedCustomerIsFoundButCannotReceive() {
        customer.setStatus(CustomerStatus.SUSPENDED);

        LookupResponse response = service.lookup("0754123210");

        assertThat(response.found()).isTrue();
        assertThat(response.canReceive()).isFalse();
    }

    @Test
    void aSuspendedWalletCannotReceive() {
        wallet.setStatus(WalletStatus.SUSPENDED);

        assertThat(service.lookup("0754123210").canReceive()).isFalse();
    }

    @Test
    void anUnknownNumberIsNotFound() {
        lenient().when(normalizer.normalize("0700000000")).thenReturn("+255700000000");
        lenient().when(userRepository.findByPhone("+255700000000")).thenReturn(Optional.empty());

        assertThat(service.lookup("0700000000")).isEqualTo(LookupResponse.notFound());
    }

    @Test
    void anAgentOrStaffNumberIsNotFoundEither() {
        lenient().when(customerRepository.findByUserId(user.getId())).thenReturn(Optional.empty());

        assertThat(service.lookup("0754123210").found()).isFalse();
    }

    @Test
    void anInvalidNumberIsNotFoundInsteadOfAnError() {
        lenient().when(normalizer.normalize("abc")).thenThrow(new IllegalArgumentException("bad"));

        assertThat(service.lookup("abc").found()).isFalse();
    }
}
