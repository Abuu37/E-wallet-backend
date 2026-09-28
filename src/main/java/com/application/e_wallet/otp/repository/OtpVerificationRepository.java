package com.application.e_wallet.otp.repository;

import com.application.e_wallet.otp.entity.OtpVerificationEntity;
import com.application.e_wallet.user.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OtpVerificationRepository extends JpaRepository<OtpVerificationEntity, UUID> {

    Optional<OtpVerificationEntity> findByUser(UserEntity user);

    void deleteByUser(UserEntity user);
}
