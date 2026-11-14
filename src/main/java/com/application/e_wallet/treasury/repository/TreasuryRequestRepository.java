package com.application.e_wallet.treasury.repository;

import com.application.e_wallet.treasury.entity.RequestStatus;
import com.application.e_wallet.treasury.entity.TreasuryRequestEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TreasuryRequestRepository extends JpaRepository<TreasuryRequestEntity, UUID> {

    List<TreasuryRequestEntity> findAllByOrderByCreatedAtDesc();

    Optional<TreasuryRequestEntity> findByRequestRef(String requestRef);

    List<TreasuryRequestEntity> findAllByAgent_IdOrderByCreatedAtDesc(UUID agentId);

    long countByStatus(RequestStatus status);

    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM TreasuryRequestEntity r WHERE r.status = :status")
    BigDecimal sumAmountByStatus(@Param("status") RequestStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r
            FROM TreasuryRequestEntity r
            WHERE r.id = :requestId
""")
    Optional<TreasuryRequestEntity> findByRequestId(@Param("requestId") UUID requestId);
}
