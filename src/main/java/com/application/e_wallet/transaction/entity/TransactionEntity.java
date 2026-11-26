package com.application.e_wallet.transaction.entity;

import com.application.e_wallet.wallet.entity.WalletEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "transactions",
        indexes = {
                @Index(name = "idx_transactions_sender_wallet", columnList = "sender_wallet_id"),
                @Index(name = "idx_transactions_receiver_wallet", columnList = "receiver_wallet_id")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The code a customer shows the agent after withdrawing. Only set for withdrawals at an agent. */
    @Column(name = "match_code", length = 8)
    private String matchCode;

    @Column(name = "reference_no", nullable = false, unique = true, length = 60)
    private String referenceNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_wallet_id", nullable = false)
    private WalletEntity senderWallet;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receiver_wallet_id", nullable = false)
    private WalletEntity receiverWallet;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal fee;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal commission;

    @Column(name = "transaction_type", nullable = false, length = 30)
    private String transactionType;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "external_reference", length = 100)
    private String externalReference;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;

        if (fee == null) {
            fee = BigDecimal.ZERO;
        }

        if (commission == null) {
            commission = BigDecimal.ZERO;
        }
    }
}
