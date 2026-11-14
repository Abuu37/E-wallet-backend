package com.application.e_wallet.module.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "modules",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_modules_name",
                        columnNames = "name"
                ),
                @UniqueConstraint(
                        name = "uk_modules_reference",
                        columnNames = "reference"
                )
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID reference;

    @Column(nullable = false, length = 50, unique = true)
    private String name;

    @Column(nullable = false, length = 255)
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.reference == null) {
            this.reference = UUID.randomUUID();
        }
    }
}
