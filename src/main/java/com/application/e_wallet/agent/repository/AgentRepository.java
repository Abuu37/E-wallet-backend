package com.application.e_wallet.agent.repository;

import com.application.e_wallet.agent.entity.AgentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AgentRepository extends JpaRepository<AgentEntity, UUID> {

    boolean existsByAgentNumber(String agentNumber);

    Optional<AgentEntity> findByUserId(UUID userid);
}
