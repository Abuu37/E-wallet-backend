package com.application.e_wallet.auth.service;

import com.application.e_wallet.auth.config.PinResetProperties;
import com.application.e_wallet.auth.entity.PinResetTokenEntity;
import com.application.e_wallet.auth.repository.PinResetTokenRepository;
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
public class PinResetService {

    private final UserRepository userRepository;
    private final PinResetTokenRepository pinResetTokenRepository;
    private final OtpGenerator otpGenerator;
    private final PinResetProperties pinResetProperties;
    private final PasswordEncoder passwordEncoder; // hashes/verifies the OTP
    private final PasswordEncoder pinEncoder; // hashes/verifies the PIN
    private final EmailService emailService;

    //======== Always called from the controller — never reveals whether the email exists ========
    @Transactional
    public void forgotPin(String email) {

        String normalizedEmail = email.trim().toLowerCase();

        Optional<UserEntity> userOptional = userRepository.findByEmail(normalizedEmail);

        if (userOptional.isEmpty()) {
            // Swallow silently — the controller returns the same generic
            // response whether or not the account exists, to prevent
            // email enumeration.
            return;
        }

        UserEntity user = userOptional.get();

        Optional<PinResetTokenEntity> existing = pinResetTokenRepository.findByUser(user);

        if (existing.isPresent()) {
            Instant cooldownEnd = existing.get().getCreatedAt()
                    .plusSeconds(pinResetProperties.getResendCooldownSeconds());

            if (cooldownEnd.isAfter(Instant.now())) {
                // Within cooldown — stay silent instead of signalling
                // "please wait", which would itself leak account existence.
                return;
            }
        }

        pinResetTokenRepository.deleteByUser(user);

        String otp = otpGenerator.generate(pinResetProperties.getLength());

        PinResetTokenEntity token = PinResetTokenEntity.builder()
                .user(user)
                .otpHash(passwordEncoder.encode(otp))
                .expiresAt(Instant.now().plusSeconds(pinResetProperties.getExpirationMinutes() * 60L))
                .attempts(0)
                .maxAttempts(pinResetProperties.getMaxAttempts())
                .used(false)
                .build();

        pinResetTokenRepository.save(token);

        try {
            emailService.sendPinResetOtp(user.getEmail(), otp, pinResetProperties.getExpirationMinutes());
        } catch (Exception exception) {
            log.error("Failed to send PIN reset email to {}", user.getEmail(), exception);
        }
    }

    @Transactional(noRollbackFor = BadRequestException.class)
    public void resetPin(String email, String otp, String newPin) {

        String normalizedEmail = email.trim().toLowerCase();

        UserEntity user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() ->
                        new BadRequestException(
                                "Invalid or expired reset code."
                        ));

        PinResetTokenEntity token = pinResetTokenRepository.findByUser(user)
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

            pinResetTokenRepository.save(token);

            throw new BadRequestException(
                    "Invalid reset code."
            );
        }

        if (pinEncoder.matches(newPin, user.getPinHash())) {
            throw new BadRequestException(
                    "New PIN must be different from current PIN."
            );
        }

        token.setUsed(true);
        token.setUsedAt(Instant.now());
        pinResetTokenRepository.save(token);

        user.setPinHash(pinEncoder.encode(newPin));
        userRepository.save(user);
    }
}
