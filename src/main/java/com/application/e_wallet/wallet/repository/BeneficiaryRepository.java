package com.application.e_wallet.wallet.repository;

import com.application.e_wallet.wallet.entity.BeneficiaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BeneficiaryRepository extends JpaRepository<BeneficiaryEntity, UUID> {

    Optional<BeneficiaryEntity> findByOwnerIdAndWalletId(UUID ownerId, UUID walletId);

    @Query("""
            SELECT b FROM BeneficiaryEntity b JOIN FETCH b.wallet w JOIN FETCH w.user
            WHERE b.owner.id = :ownerId ORDER BY b.lastUsedAt DESC NULLS LAST, b.createdAt DESC
            """)
    List<BeneficiaryEntity> findRecent(@Param("ownerId") UUID ownerId);
}
