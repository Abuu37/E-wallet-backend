package com.application.e_wallet.auth.repository;

import com.application.e_wallet.auth.entity.UserSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserSessionRepository extends JpaRepository<UserSessionEntity, UUID> {

    Optional<UserSessionEntity> findByRefreshTokenHash(String refreshTokenHash);

    void deleteAllByUserId(UUID userId);
}
