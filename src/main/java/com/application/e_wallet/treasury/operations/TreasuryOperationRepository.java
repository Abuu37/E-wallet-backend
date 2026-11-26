package com.application.e_wallet.treasury.operations;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TreasuryOperationRepository extends JpaRepository<TreasuryOperationEntity, UUID> {

    /** Locked so two staff members cannot decide the same operation at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM TreasuryOperationEntity o WHERE o.id = :id")
    Optional<TreasuryOperationEntity> lockById(@Param("id") UUID id);

    List<TreasuryOperationEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<TreasuryOperationEntity> findAllByStatusOrderByCreatedAtDesc(OperationStatus status, Pageable pageable);

    /** A transfer can have one reversal waiting or done, never two. */
    boolean existsByTargetReferenceAndOpTypeAndStatusIn(String targetReference, OperationType opType, java.util.Collection<OperationStatus> statuses);
}
