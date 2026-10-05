package com.application.e_wallet.wallet.entity;

import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.financial_account.entity.FinancialAccountEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@AllArgsConstructor
@NoArgsConstructor
@Table(
        name = "wallets",
        indexes = {
                @Index(
                        name = "idx_wallet_customer_id",
                        columnList = "customer_id",
                        unique = true
                ),
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
public class WalletEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "customer_id",
            nullable = false,
            unique = true
    )
    private CustomerEntity customer;

    @Column(
            name = "wallet_number",
            nullable = false,
            unique = true,
            length = 30
    )
    private String walletNumber;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WalletStatus status;

    @OneToOne(
            mappedBy = "wallet",
            fetch = FetchType.LAZY,
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    private FinancialAccountEntity financialAccount;

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
    }
    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }


}
