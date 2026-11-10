package com.application.e_wallet.location.repository;

import com.application.e_wallet.location.entity.RegionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RegionRepository extends JpaRepository<RegionEntity, UUID> {

    List<RegionEntity> findAllByOrderByNameAsc();

    Optional<RegionEntity> findByExternalId(String externalId);
}
