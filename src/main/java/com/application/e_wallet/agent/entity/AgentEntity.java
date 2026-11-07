package com.application.e_wallet.agent.entity;

import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.wallet.entity.WalletEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "agents",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_agent_user_id",
                        columnNames = "user_id"
                ),
                @UniqueConstraint(
                        name = "uk_agents_agent_number",
                        columnNames = "agent_number"
                )
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            unique = true
    )
    private UserEntity user;

    @Column(
            name = "agent_number",
            nullable = false,
            unique = true,
            length = 30
    )
    private String agentNumber;

    // One agent has one float wallet.
    @OneToOne(
            mappedBy = "agent",
            fetch = FetchType.LAZY
    )
    private WalletEntity wallet;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "update_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();

        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

}
