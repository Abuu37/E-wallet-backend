package com.application.e_wallet.company_wallet.repository;

import com.application.e_wallet.company_wallet.entity.CompanyMasterWalletEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface CompanyMasterWalletRepository extends JpaRepository<CompanyMasterWalletEntity, UUID> {

    @Query("SELECT c FROM CompanyMasterWalletEntity c WHERE c.singletonKey = 1")
    Optional<CompanyMasterWalletEntity> findSingleton();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CompanyMasterWalletEntity c WHERE c.singletonKey = 1")
    Optional<CompanyMasterWalletEntity> findSingletonForUpdate();
}
