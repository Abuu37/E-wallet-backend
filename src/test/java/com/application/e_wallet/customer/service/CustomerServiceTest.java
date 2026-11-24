package com.application.e_wallet.customer.service;

import com.application.e_wallet.auth.dto.CustomerRegistrationRequest;
import com.application.e_wallet.common.exception.DuplicationResourceException;
import com.application.e_wallet.common.validation.PhoneNumberNormalizer;
import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.customer.entity.CustomerStatus;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.role.entity.RoleEntity;
import com.application.e_wallet.role.repository.RoleRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.entity.UserStatus;
import com.application.e_wallet.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock private com.application.e_wallet.audit.service.AuditService auditService;
    @Mock private CustomerRepository customerRepository;
    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private PhoneNumberNormalizer phoneNumberNormalizer;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private CustomerService customerService;

    @Test
    void registerCustomerBuildsUserAndCustomerFromRequest() {
        CustomerRegistrationRequest request = new CustomerRegistrationRequest();
        request.setUsername("jdoe");
        request.setFirstName("Jane");
        request.setMiddleName("M");
        request.setLastName("Doe");
        request.setEmail("jane.doe@example.com");
        request.setPhone("0712345678");
        request.setNidaNumber("1234567890123456");
        request.setDateOfBirth(LocalDate.of(1995, 1, 1));
        request.setAddress("123 Main St");

        RoleEntity customerRole = RoleEntity.builder().id(UUID.randomUUID()).name("CUSTOMER").build();

        lenient().when(userRepository.existsByEmail(anyString())).thenReturn(false);
        lenient().when(userRepository.existsByPhone(anyString())).thenReturn(false);
        when(phoneNumberNormalizer.normalize("0712345678")).thenReturn("+255712345678");
        when(roleRepository.findByName("CUSTOMER")).thenReturn(Optional.of(customerRole));

        UUID userId = UUID.randomUUID();
        when(userRepository.save(any())).thenAnswer(inv -> {
            UserEntity user = inv.getArgument(0);
            user.setId(userId);
            if (user.getStatus() == null) {
                user.setStatus(UserStatus.AWAITING_PIN);
            }
            return user;
        });
        when(customerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        customerService.registerCustomer(request);

        ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(userCaptor.capture());
        UserEntity savedUser = userCaptor.getValue();
        assertThat(savedUser.getRole()).isEqualTo(customerRole);
        assertThat(savedUser.getLoginUsername()).isEqualTo("jdoe");

        ArgumentCaptor<CustomerEntity> customerCaptor = ArgumentCaptor.forClass(CustomerEntity.class);
        verify(customerRepository).save(customerCaptor.capture());
        CustomerEntity savedCustomer = customerCaptor.getValue();
        assertThat(savedCustomer.getFirstName()).isEqualTo("Jane");
        assertThat(savedCustomer.getLastName()).isEqualTo("Doe");
        assertThat(savedCustomer.getNidaNumber()).isEqualTo("1234567890123456");
    }

    @Test
    void registerCustomerThrowsDuplicationResourceExceptionOnDuplicateUsername() {
        CustomerRegistrationRequest request = new CustomerRegistrationRequest();
        request.setUsername("jdoe");
        request.setFirstName("Jane");
        request.setMiddleName("M");
        request.setLastName("Doe");
        request.setEmail("jane.doe@example.com");
        request.setPhone("0712345678");
        request.setNidaNumber("1234567890123456");
        request.setDateOfBirth(LocalDate.of(1995, 1, 1));
        request.setAddress("123 Main St");

        lenient().when(userRepository.existsByEmail(anyString())).thenReturn(false);
        lenient().when(userRepository.existsByPhone(anyString())).thenReturn(false);
        lenient().when(phoneNumberNormalizer.normalize("0712345678")).thenReturn("+255712345678");
        when(userRepository.existsByLoginUsername("jdoe")).thenReturn(true);

        assertThatThrownBy(() -> customerService.registerCustomer(request))
                .isInstanceOf(DuplicationResourceException.class)
                .hasMessageContaining("Username");

        verify(userRepository, never()).save(any());
    }

    @Test
    void activateCustomerSetsStatusActive() {
        UUID userId = UUID.randomUUID();
        CustomerEntity customer = CustomerEntity.builder()
                .status(CustomerStatus.PENDING)
                .build();

        when(customerRepository.findByUserId(userId)).thenReturn(Optional.of(customer));
        when(customerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CustomerEntity activated = customerService.activateCustomer(userId);

        assertThat(activated.getStatus()).isEqualTo(CustomerStatus.ACTIVE);
    }
}
