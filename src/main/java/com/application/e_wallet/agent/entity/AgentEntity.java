package com.application.e_wallet.agent.entity;

import com.application.e_wallet.location.entity.DistrictEntity;
import com.application.e_wallet.location.entity.RegionEntity;
import com.application.e_wallet.user.entity.UserEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
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

    @Column(name = "reference", nullable = false, unique = true)
    private UUID reference;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn( name = "user_id", nullable = false, unique = true)
    private UserEntity user;

    @Column(name = "agent_number", nullable = false, unique = true, length = 30)
    private String agentNumber;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "middle_name", length = 100)
    private String middleName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "phone_number", nullable = false, length = 30)
    private String phoneNumber;

    @Column(name = "business_name", nullable = false, length = 150)
    private String businessName;

    @Column(name = "tin_number", nullable = false, length = 30)
    private String tinNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "agent_status", nullable = false, length = 20)
    private AgentStatus agentStatus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private UserEntity createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", nullable = false, length = 10)
    private Gender gender;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "region_id", nullable = false)
    private RegionEntity region;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "district_id", nullable = false)
    private DistrictEntity district;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "update_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();

        this.createdAt = now;
        this.updatedAt = now;

        if (this.reference == null) {
            this.reference = UUID.randomUUID();
        }

        if (this.agentStatus == null) {
            this.agentStatus = AgentStatus.ACTIVE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

}
