package com.application.e_wallet.wallet.repository;

import com.application.e_wallet.wallet.entity.WalletEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<WalletEntity, UUID> {

    boolean existsByCustomerId(UUID customerId);

    Optional<WalletEntity> findByCustomerId(UUID customerId);

    Optional<WalletEntity> findByWalletNumber(String walletNumber);
}
