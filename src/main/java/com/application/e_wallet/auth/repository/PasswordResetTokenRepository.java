package com.application.e_wallet.auth.repository;

import com.application.e_wallet.auth.entity.PasswordResetTokenEntity;
import com.application.e_wallet.user.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetTokenEntity, UUID> {

    Optional<PasswordResetTokenEntity> findByUser(UserEntity user);

    void deleteByUser(UserEntity user);
}
