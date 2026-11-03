package com.application.e_wallet.auth.repository;

import com.application.e_wallet.auth.entity.PinResetTokenEntity;
import com.application.e_wallet.user.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PinResetTokenRepository extends JpaRepository<PinResetTokenEntity, UUID> {

    Optional<PinResetTokenEntity> findByUser(UserEntity user);

    void deleteByUser(UserEntity user);
}
