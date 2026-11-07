package com.application.e_wallet.company_wallet.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "company_master_wallet")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyMasterWalletEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "singleton_key", nullable = false, unique = true)
    private Short singletonKey;

    @Column(nullable = false)
    private String walletName;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal unallocatedFloat;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal totalDistributedFloat;

    @Column(nullable = true)
    private String escrowBankAccountNo;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;

        if (singletonKey == null) {
            singletonKey = 1;
        }

        if (unallocatedFloat == null) {
            unallocatedFloat = BigDecimal.ZERO;
        }

        if (totalDistributedFloat == null) {
            totalDistributedFloat = BigDecimal.ZERO;
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
