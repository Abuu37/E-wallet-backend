package com.application.e_wallet.customer.service;

import com.application.e_wallet.auth.dto.CustomerRegistrationRequest;
import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.user.entity.UserEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @InjectMocks
    private CustomerService customerService;

    @Test
    void createCustomerPopulatesKycFieldsFromRequest() {
        UUID userId = UUID.randomUUID();
        UserEntity user = UserEntity.builder()
                .id(userId)
                .email("jane.doe@example.com")
                .phone("+255712345678")
                .build();

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

        when(customerRepository.existsByUserId(userId)).thenReturn(false);
        when(customerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CustomerEntity customer = customerService.createCustomer(user, request);

        assertThat(customer.getFirstName()).isEqualTo("Jane");
        assertThat(customer.getMiddleName()).isEqualTo("M");
        assertThat(customer.getLastName()).isEqualTo("Doe");
        assertThat(customer.getEmail()).isEqualTo(user.getEmail());
        assertThat(customer.getPhoneNumber()).isEqualTo(user.getPhone());
        assertThat(customer.getNidaNumber()).isEqualTo("1234567890123456");
        assertThat(customer.getDateOfBirth()).isEqualTo(LocalDate.of(1995, 1, 1));
        assertThat(customer.getAddress()).isEqualTo("123 Main St");
    }
}
