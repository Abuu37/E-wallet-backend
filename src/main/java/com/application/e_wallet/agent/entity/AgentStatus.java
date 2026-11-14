package com.application.e_wallet.agent.entity;

// The agent's own operational status - independent of the linked UserEntity.status,
// which only governs whether the account can log in at all.
public enum AgentStatus {
    ACTIVE,
    SUSPENDED,
    DEACTIVATED
}
