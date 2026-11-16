package com.application.e_wallet.customer.repository;

import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.customer.entity.CustomerStatus;
import org.springframework.data.jpa.repository.JpaRepository;


import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<CustomerEntity, UUID> {

    boolean existsByUserId(UUID userId);

    Optional<CustomerEntity> findByUserId(UUID userId);

    Optional<CustomerEntity> findByCustomerNumber(String customerNumber);

    long countByStatus(CustomerStatus status);
}
