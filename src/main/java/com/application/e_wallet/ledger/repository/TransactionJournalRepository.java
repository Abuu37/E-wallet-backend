package com.application.e_wallet.ledger.repository;

import com.application.e_wallet.ledger.entity.TransactionJournalEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TransactionJournalRepository extends JpaRepository<TransactionJournalEntity, UUID> {

    boolean existsByReferenceNumber(String referenceNumber);

    @EntityGraph(attributePaths = "lines")
    List<TransactionJournalEntity> findAllByOrderByCreatedAtDesc();
}
