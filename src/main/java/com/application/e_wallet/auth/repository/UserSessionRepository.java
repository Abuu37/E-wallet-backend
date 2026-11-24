package com.application.e_wallet.auth.repository;

import com.application.e_wallet.auth.entity.UserSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface UserSessionRepository extends JpaRepository<UserSessionEntity, UUID> {

    Optional<UserSessionEntity> findByRefreshTokenHash(String refreshTokenHash);

    void deleteAllByUserId(UUID userId);

    @Modifying
    @Query("update UserSessionEntity s set s.revoked = true, s.revokedAt = :now where s.familyId = :familyId and s.revoked = false")
    void revokeFamily(@Param("familyId") UUID familyId, @Param("now") OffsetDateTime now);
}
