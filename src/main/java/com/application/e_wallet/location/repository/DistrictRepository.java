package com.application.e_wallet.location.repository;

import com.application.e_wallet.location.entity.DistrictEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DistrictRepository extends JpaRepository<DistrictEntity, UUID> {

    List<DistrictEntity> findAllByRegion_ExternalIdOrderByNameAsc(String regionExternalId);

    Optional<DistrictEntity> findByExternalId(String externalId);
}
