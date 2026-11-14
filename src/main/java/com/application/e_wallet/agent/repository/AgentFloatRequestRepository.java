package com.application.e_wallet.agent.repository;

import com.application.e_wallet.agent.entity.AgentFloatRequestEntity;
import com.application.e_wallet.agent.entity.FloatRequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentFloatRequestRepository extends JpaRepository<AgentFloatRequestEntity, UUID> {

    List<AgentFloatRequestEntity> findAllByOrderByCreatedAtDesc();

    Optional<AgentFloatRequestEntity> findByRequestRef(String requestRef);

    List<AgentFloatRequestEntity> findAllByAgent_IdOrderByCreatedAtDesc(UUID agentId);

    long countByStatus(FloatRequestStatus status);

    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM AgentFloatRequestEntity r WHERE r.status = :status")
    BigDecimal sumAmountByStatus(@Param("status") FloatRequestStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r
            FROM AgentFloatRequestEntity r
            WHERE r.id = :requestId
""")
    Optional<AgentFloatRequestEntity> findByRequestId(@Param("requestId") UUID requestId);
}
