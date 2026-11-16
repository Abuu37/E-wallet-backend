package com.application.e_wallet.ledger.repository;

import com.application.e_wallet.ledger.entity.TransactionJournalLineEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionJournalLineRepository extends JpaRepository<TransactionJournalLineEntity, UUID> {

    @EntityGraph(attributePaths = "journal")
    Optional<TransactionJournalLineEntity> findFirstByWalletIdOrderByJournal_CreatedAtDesc(UUID walletId);

    @EntityGraph(attributePaths = "journal")
    List<TransactionJournalLineEntity> findTop5ByWalletIdInOrderByJournal_CreatedAtDesc(List<UUID> walletIds);
}
