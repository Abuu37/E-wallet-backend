package com.application.e_wallet.ledger.repository;

import com.application.e_wallet.ledger.entity.EntryType;
import com.application.e_wallet.ledger.entity.TransactionJournalLineEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionJournalLineRepository extends JpaRepository<TransactionJournalLineEntity, UUID> {

    @EntityGraph(attributePaths = "journal")
    Optional<TransactionJournalLineEntity> findFirstByWalletIdOrderByJournal_CreatedAtDesc(UUID walletId);

    @EntityGraph(attributePaths = "journal")
    List<TransactionJournalLineEntity> findTop5ByWalletIdInOrderByJournal_CreatedAtDesc(List<UUID> walletIds);

    @Query("SELECT COALESCE(SUM(l.amount), 0) FROM TransactionJournalLineEntity l WHERE l.entryType = :entryType")
    BigDecimal sumAmountByEntryType(@Param("entryType") EntryType entryType);
}
