package com.application.e_wallet.module.repository;

import com.application.e_wallet.module.entity.ModuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ModuleRepository extends JpaRepository<ModuleEntity, UUID> {

    Optional<ModuleEntity> findByName(String name);
}
