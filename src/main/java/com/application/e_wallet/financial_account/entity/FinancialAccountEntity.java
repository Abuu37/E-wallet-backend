package com.application.e_wallet.financial_account.entity;

import com.application.e_wallet.wallet.entity.WalletEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "financial_accounts",
        indexes = {
                @Index(
                        name = "idx_financial_account_number",
                        columnList = "account_number",
                        unique = true
                ),
                @Index(
                        name = "idx_financial_account_wallet_id",
                        columnList = "wallet_id",
                        unique = true
                )
        }
)

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinancialAccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(
            name = "account_number",
            nullable = false,
            unique = true,
            length = 30
    )
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column (
            name = "account_type",
            nullable = false,
            length = 30
    )
    private FinancialAccountType accountType;

    @Column(
            nullable = false, length = 3
    )
    private String currency;

    @Column(
            name = "current_balance",
            nullable = false,
            precision = 19,
            scale = 2

    )
    private BigDecimal currentBalance;

    @Enumerated(EnumType.STRING)
    @Column (nullable = false, length = 20)
    private FinancialAccountStatus status;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "wallet_id",
            nullable = false,
            unique = true
    )
    private WalletEntity wallet;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {

        OffsetDateTime now = OffsetDateTime.now();
        this.updatedAt = now;
        this.createdAt = now;

        if (currentBalance == null) {
            currentBalance = BigDecimal.ZERO;
        }

        if (status == null) {
            status = FinancialAccountStatus.ACTIVE;
        }

        if ( currency == null) {
            currency = "TZS";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt= OffsetDateTime.now();
    }

}
