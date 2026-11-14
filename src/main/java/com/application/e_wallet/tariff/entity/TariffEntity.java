package com.application.e_wallet.tariff.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "tariffs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TariffEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "reference", nullable = false, unique = true)
    private UUID reference;

    @Column(name = "transaction_type", nullable = false, length = 30)
    private String transactionType;

    @Column(name = "min_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal minAmount;

    @Column(name = "max_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal maxAmount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal fee;

    @Column(name = "agent_commission", nullable = false, precision = 19, scale = 2)
    private BigDecimal agentCommission;

    @Column(nullable = false)
    private Boolean active;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;

        if (reference == null) {
            reference = UUID.randomUUID();
        }

        if (active == null) {
            active = true;
        }
    }
}
