package com.application.e_wallet.transaction.repository;

import com.application.e_wallet.transaction.entity.TransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<TransactionEntity, UUID> {
    List<TransactionEntity> findByStatusAndCreatedAtBetween(String status, OffsetDateTime start, OffsetDateTime end);
}
