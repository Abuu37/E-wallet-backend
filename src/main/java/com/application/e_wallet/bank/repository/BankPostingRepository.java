package com.application.e_wallet.bank.repository;

import com.application.e_wallet.bank.entity.BankPostingEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BankPostingRepository extends JpaRepository<BankPostingEntity, UUID> {

    /** Locked so the after-commit attempt and the retry job never post the same line twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM BankPostingEntity p WHERE p.id = :id")
    Optional<BankPostingEntity> lockById(@Param("id") UUID id);

    @Query("SELECT p.id FROM BankPostingEntity p WHERE p.status = 'PENDING' AND p.createdAt < :olderThan ORDER BY p.createdAt")
    List<UUID> findPendingOlderThan(@Param("olderThan") OffsetDateTime olderThan);

    Optional<BankPostingEntity> findByTransactionId(UUID transactionId);
}
