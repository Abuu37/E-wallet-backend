package com.application.e_wallet.customer.service;


import com.application.e_wallet.common.exception.DuplicationResourceException;
import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.customer.entity.CustomerStatus;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.user.entity.UserEntity;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;

    @Transactional
    public CustomerEntity createCustomer(UserEntity user){

        if (customerRepository.existsByUserId(user.getId())) {
            throw new DuplicationResourceException(
                    "Customer already exists for user id: " + user.getId()
            );
        }

        CustomerEntity customer = CustomerEntity.builder()
                .user(user)
                .customerNumber(generateCustomerNumber())
                .status(CustomerStatus.PENDING)
                .build();

        return customerRepository.save(customer);

    }

    @Transactional
    public CustomerEntity activateCustomer(UUID userId){
        CustomerEntity customer = customerRepository
                .findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Customer not found"));

        customer.setStatus(CustomerStatus.ACTIVE);
        return customerRepository.save(customer);
    }

    private String generateCustomerNumber(){

        return "CUS-" +
                UUID.randomUUID()
                        .toString()
                        .replace("-", "")
                        .substring(0, 12)
                        .toUpperCase();
    }
}
