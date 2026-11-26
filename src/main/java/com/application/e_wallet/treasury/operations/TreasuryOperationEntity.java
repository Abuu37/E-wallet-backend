package com.application.e_wallet.treasury.operations;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A treasury move that needs two different staff members: one asks, another approves. */
@Entity
@Table(name = "treasury_operations")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TreasuryOperationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "op_ref", nullable = false, unique = true, length = 20)
    private String opRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "op_type", nullable = false, length = 30)
    private OperationType opType;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 500)
    private String reason;

    /** The transfer being reversed. Only reversals have one. */
    @Column(name = "target_reference", length = 60)
    private String targetReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private OperationStatus status = OperationStatus.PENDING;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    /** The journal or the new transaction the approval produced. */
    @Column(name = "result_reference", length = 60)
    private String resultReference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
        if (opRef == null) {
            opRef = "OP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        }
    }
}
