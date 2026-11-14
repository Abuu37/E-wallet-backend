package com.application.e_wallet.staff.entity;

import com.application.e_wallet.user.entity.UserEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "staff",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_staff_user_id", columnNames = "user_id"),
                @UniqueConstraint(name = "uk_staff_staff_no", columnNames = "staff_no")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StaffEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "reference", nullable = false, unique = true)
    private UUID reference;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private UserEntity user;

    @Column(name = "staff_no", nullable = false, unique = true, length = 30)
    private String staffNo;

    @Column(name = "job_title", nullable = false, length = 100)
    private String jobTitle;

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

    // Nullable - the bootstrap admin's staff row has no acting creator.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private UserEntity createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StaffStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();

        this.createdAt = now;
        this.updatedAt = now;

        if (this.reference == null) {
            this.reference = UUID.randomUUID();
        }

        if (this.status == null) {
            this.status = StaffStatus.ACTIVE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
