package com.application.e_wallet.customer.entity;

import com.application.e_wallet.user.entity.UserEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "customers",
        indexes = {
                @Index(
                        name = "idx_customers_user_id",
                        columnList = "user_id",
                        unique = true
                ),
                @Index(
                        name = "idx_customers_customer_number",
                        columnList = "customer_number",
                        unique = true
                )
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            unique = true
    )
    private UserEntity user;

    @Column(
            name = "customer_number",
            nullable = false,
            unique = true,
            length = 30
    )
    private String customerNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CustomerStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();

        createdAt = now;
        updatedAt = now;

        if (status == null) {
            status = CustomerStatus.PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}