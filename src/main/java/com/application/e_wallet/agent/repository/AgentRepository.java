package com.application.e_wallet.agent.repository;

import com.application.e_wallet.agent.entity.AgentEntity;
import com.application.e_wallet.agent.entity.AgentStatus;
import com.application.e_wallet.wallet.entity.WalletType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentRepository extends JpaRepository<AgentEntity, UUID> {

    boolean existsByAgentNumber(String agentNumber);

    Optional<AgentEntity> findByAgentNumber(String agentNumber);

    Optional<AgentEntity> findByUserId(UUID userId);

    long countByAgentStatus(AgentStatus agentStatus);

    Page<AgentEntity> findByAgentStatus(AgentStatus agentStatus, Pageable pageable);

    @Query("""
            SELECT a.id AS agentId, a.agentNumber AS agentCode, a.businessName AS businessName,
                   a.phoneNumber AS phoneNumber, w.accountNumber AS floatAccountNumber, w.balance AS currentBalance
            FROM AgentEntity a
            JOIN WalletEntity w ON w.user.id = a.user.id
            WHERE w.walletType = :walletType AND w.balance < :threshold AND a.agentStatus = :agentStatus
            ORDER BY w.balance ASC
            """)
    List<LowFloatAgentProjection> findLowFloatAgents(
            @Param("walletType") WalletType walletType,
            @Param("threshold") BigDecimal threshold,
            @Param("agentStatus") AgentStatus agentStatus);
}
