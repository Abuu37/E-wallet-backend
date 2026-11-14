package com.application.e_wallet.staff.repository;

import com.application.e_wallet.staff.entity.StaffEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StaffRepository extends JpaRepository<StaffEntity, UUID> {

    Optional<StaffEntity> findByUserId(UUID userId);

    boolean existsByStaffNo(String staffNo);
}
