package com.application.e_wallet.location.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "regions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Stable human-readable code (e.g. "TZ01") used by API consumers and onboarding requests.
    @Column(name = "external_id", nullable = false, unique = true, length = 100)
    private String externalId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, unique = true, length = 100)
    private String slug;
}
