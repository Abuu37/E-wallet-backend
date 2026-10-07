package com.application.e_wallet.auth.service;

import com.application.e_wallet.auth.config.PasswordResetProperties;
import com.application.e_wallet.auth.entity.PasswordResetTokenEntity;
import com.application.e_wallet.auth.repository.PasswordResetTokenRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.otp.service.EmailService;
import com.application.e_wallet.otp.service.OtpGenerator;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final OtpGenerator otpGenerator;
    private final PasswordResetProperties passwordResetProperties;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    //======== Always called from the controller — never reveals whether the email exists ========
    @Transactional
    public void forgotPassword(String email) {

        String normalizedEmail = email.trim().toLowerCase();

        Optional<UserEntity> userOptional = userRepository.findByEmail(normalizedEmail);

        if (userOptional.isEmpty()) {
            // Swallow silently — the controller returns the same generic
            // response whether or not the account exists, to prevent
            // email enumeration.
            return;
        }

        UserEntity user = userOptional.get();

        Optional<PasswordResetTokenEntity> existing = passwordResetTokenRepository.findByUser(user);

        if (existing.isPresent()) {
            Instant cooldownEnd = existing.get().getCreatedAt()
                    .plusSeconds(passwordResetProperties.getResendCooldownSeconds());

            if (cooldownEnd.isAfter(Instant.now())) {
                // Within cooldown — stay silent instead of signalling
                // "please wait", which would itself leak account existence.
                return;
            }
        }

        passwordResetTokenRepository.deleteByUser(user);

        String otp = otpGenerator.generate(passwordResetProperties.getLength());

        PasswordResetTokenEntity token = PasswordResetTokenEntity.builder()
                .user(user)
                .otpHash(passwordEncoder.encode(otp))
                .expiresAt(Instant.now().plusSeconds(passwordResetProperties.getExpirationMinutes() * 60L))
                .attempts(0)
                .maxAttempts(passwordResetProperties.getMaxAttempts())
                .used(false)
                .build();

        passwordResetTokenRepository.save(token);

        try {
            emailService.sendPasswordResetOtp(user.getEmail(), otp, passwordResetProperties.getExpirationMinutes());
        } catch (Exception exception) {
            log.error("Failed to send password reset email to {}", user.getEmail(), exception);
        }
    }

    @Transactional(noRollbackFor = BadRequestException.class)
    public void resetPassword(String email, String otp, String newPassword) {

        String normalizedEmail = email.trim().toLowerCase();

        UserEntity user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() ->
                        new BadRequestException(
                                "Invalid or expired reset code."
                        ));

        PasswordResetTokenEntity token = passwordResetTokenRepository.findByUser(user)
                .orElseThrow(() ->
                        new BadRequestException(
                                "Invalid or expired reset code."
                        ));

        if (token.isUsed()) {
            throw new BadRequestException(
                    "This reset code has already been used."
            );
        }

        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException(
                    "Reset code has expired."
            );
        }

        if (token.getAttempts() >= token.getMaxAttempts()) {
            throw new BadRequestException(
                    "Maximum reset attempts exceeded. Please request a new code."
            );
        }

        if (!passwordEncoder.matches(otp, token.getOtpHash())) {

            token.setAttempts(token.getAttempts() + 1);

            passwordResetTokenRepository.save(token);

            throw new BadRequestException(
                    "Invalid reset code."
            );
        }

        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BadRequestException(
                    "New password must be different from current password."
            );
        }

        token.setUsed(true);
        token.setUsedAt(Instant.now());
        passwordResetTokenRepository.save(token);

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }
}
