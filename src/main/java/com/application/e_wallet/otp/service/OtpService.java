package com.application.e_wallet.otp.service;

import com.application.e_wallet.auth.service.PinResetService;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.common.validation.PhoneNumberNormalizer;
import com.application.e_wallet.notification.NotificationKey;
import com.application.e_wallet.notification.NotificationRecipient;
import com.application.e_wallet.notification.Notifier;
import com.application.e_wallet.otp.config.OtpProperties;
import com.application.e_wallet.otp.entity.OtpVerificationEntity;
import com.application.e_wallet.otp.repository.OtpVerificationRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.entity.UserStatus;
import com.application.e_wallet.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

    private static final String INVALID_CODE = "Invalid or expired verification code.";

    private final OtpVerificationRepository otpVerificationRepository;
    private final OtpGenerator otpGenerator;
    private final OtpProperties otpProperties;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final Notifier notifier;
    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final PinResetService pinResetService;

    //============ Generate Otp =============== //
    public String generateAndStoreOtp(UserEntity user) {
        try {
            otpVerificationRepository.deleteByUser(user);

            String otp = otpGenerator.generate(otpProperties.getLength());

            OtpVerificationEntity verification = OtpVerificationEntity.builder()
                    .user(user)
                    .otpHash(passwordEncoder.encode(otp))
                    .expiresAt(Instant.now().plusSeconds(otpProperties.getExpirationMinutes() * 60L))
                    .attempts(0)
                    .maxAttempts(otpProperties.getMaxAttempts())
                    .verified(false)
                    .build();

            otpVerificationRepository.save(verification);
            log.info("OTP generated for user {} (expiresAt={})", user.getId(), verification.getExpiresAt());
            return otp;
        } catch (Exception e) {
            log.error("  error ======{}", e.getMessage());
            throw new BadRequestException(e.getMessage());
        }
    }

    //======== Shared by registration and resend: generate, store, then send (after the commit) ========
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendVerificationOtp(UserEntity user) {
        String otp = generateAndStoreOtp(user);

        notifier.notify(NotificationKey.S1, NotificationRecipient.of(user),
                Map.of("code", otp, "minutes", String.valueOf(otpProperties.getExpirationMinutes())));
    }

    // ============= Resend Otp =============== //
    @Transactional
    public void resendOtp(String phone) {
        UserEntity user = findByPhone(phone)
                .orElseThrow(() -> new BadRequestException("Unable to process OTP request."));

        if (user.getStatus() == UserStatus.ACTIVE) {
            throw new BadRequestException("Account has already been verified.");
        }
        if (user.getStatus() != UserStatus.AWAITING_PIN) {
            throw new BadRequestException("Account is not eligible for verification.");
        }

        sendVerificationOtp(user);
    }

    //==============  verify otp =============== //
    // One endpoint for both screens: a person still setting up their account is checking a registration code,
    // anyone else is checking a PIN reset code.
    @Transactional(noRollbackFor = BadRequestException.class)
    public void verifyOtp(String phone, String otp) {
        UserEntity user = findByPhone(phone).orElseThrow(() -> new BadRequestException(INVALID_CODE));

        if (user.getStatus() != UserStatus.AWAITING_PIN) {
            pinResetService.verifyResetCode(phone, otp);
            return;
        }

        OtpVerificationEntity verification = otpVerificationRepository.findByUser(user)
                .orElseThrow(() -> new BadRequestException(INVALID_CODE));

        if (verification.isVerified()) {
            throw new BadRequestException("Account has already been verified.");
        }
        if (verification.getExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException("Verification code has expired.");
        }
        if (verification.getAttempts() >= verification.getMaxAttempts()) {
            throw new BadRequestException("Maximum verification attempts exceeded.");
        }
        if (!passwordEncoder.matches(otp, verification.getOtpHash())) {
            verification.setAttempts(verification.getAttempts() + 1);
            otpVerificationRepository.save(verification);
            throw new BadRequestException("Invalid verification code.");
        }

        verification.setVerified(true);
        verification.setVerifiedAt(Instant.now());
        otpVerificationRepository.save(verification);

        user.setStatus(UserStatus.AWAITING_PIN);
        userRepository.save(user);

        log.info("OTP verified for user {}", user.getId());
    }

    private Optional<UserEntity> findByPhone(String phone) {
        try {
            return userRepository.findByPhone(phoneNumberNormalizer.normalize(phone));
        } catch (RuntimeException invalidNumber) {
            return Optional.empty();
        }
    }
}
