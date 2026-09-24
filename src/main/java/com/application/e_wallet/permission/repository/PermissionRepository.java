package com.application.e_wallet.permission.repository;

import com.application.e_wallet.permission.entity.PermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PermissionRepository extends JpaRepository<PermissionEntity, UUID> {

    Optional<PermissionEntity> findByName(String name);

    boolean existsByName(String name);
}
