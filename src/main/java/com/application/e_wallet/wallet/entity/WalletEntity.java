package com.application.e_wallet.wallet.entity;

import com.application.e_wallet.user.entity.UserEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "wallets",
        indexes = {
                @Index(
                        name = "idx_wallet_wallet_number",
                        columnList = "wallet_number",
                        unique = true
                )
        }
)

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "reference", nullable = false, unique = true)
    private UUID reference;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Enumerated(EnumType.STRING)
    @Column(name = "wallet_type", nullable = false, length = 30)
    private WalletType walletType;

    @Column(
            name = "wallet_number",
            nullable = false,
            unique = true,
            length = 30
    )
    private String walletNumber;

    @Column(name = "account_number", nullable = false, unique = true, length = 30)
    private String accountNumber;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WalletStatus status;

    @Column(nullable = false)
    private Long version;

    @Column(name = "account_name", nullable = false, length = 150)
    private String accountName;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;

        if (status == null) {
            status = WalletStatus.ACTIVE;
        }

        if (currency == null) {
            currency = "TZS";
        }

        if (balance == null) {
            balance = BigDecimal.ZERO;
        }

        if (version == null) {
            version = 0L;
        }

        if (reference == null) {
            reference = UUID.randomUUID();
        }
    }
    @PreUpdate
    protected void onUpdate() {

        updatedAt = OffsetDateTime.now();
    }

}
