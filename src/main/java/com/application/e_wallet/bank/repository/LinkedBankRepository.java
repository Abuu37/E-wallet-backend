package com.application.e_wallet.bank.repository;

import com.application.e_wallet.bank.entity.LinkedBankEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LinkedBankRepository extends JpaRepository<LinkedBankEntity, UUID> {

    List<LinkedBankEntity> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, String status);

    Optional<LinkedBankEntity> findByIdAndUserIdAndStatus(UUID id, UUID userId, String status);

    Optional<LinkedBankEntity> findByUserIdAndProviderAccountId(UUID userId, String providerAccountId);
}
