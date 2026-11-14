package com.application.e_wallet.customer.entity;

import com.application.e_wallet.user.entity.UserEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
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

    @Column(name = "reference", nullable = false, unique = true)
    private UUID reference;

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

    @Column(name = "nida_number", nullable = false, length = 30)
    private String nidaNumber;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Column(name = "address", nullable = false, length = 255)
    private String address;

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

        if (reference == null) {
            reference = UUID.randomUUID();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}