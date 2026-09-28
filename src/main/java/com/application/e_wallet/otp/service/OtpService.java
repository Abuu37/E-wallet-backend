package com.application.e_wallet.otp.service;

import com.application.e_wallet.otp.config.OtpProperties;
import com.application.e_wallet.otp.entity.OtpVerificationEntity;
import com.application.e_wallet.otp.repository.OtpVerificationRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.entity.UserStatus;
import com.application.e_wallet.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

    private final OtpVerificationRepository otpVerificationRepository;
    private final OtpGenerator otpGenerator;
    private final OtpProperties otpProperties;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final EmailService emailService;

    //============ Generate Otp =============== //
    @Transactional
    public String generateAndStoreOtp(UserEntity user) {

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

        return otp;
    }

    //======== Shared by registration and resend: generate, store, then email ========
    public void sendVerificationOtp(UserEntity user) {

        String otp = generateAndStoreOtp(user);

        try {
            emailService.sendVerificationOtp(user.getEmail(), otp);
        } catch (Exception exception) {
            log.error("Failed to send OTP email to {}", user.getEmail(), exception);
        }
    }

    // ============= Resend Otp =============== //
    @Transactional
    public void resendOtp(String email) {

        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new BadRequestException(
                                "Unable to process OTP request."
                        ));

        if (user.getStatus() == UserStatus.ACTIVE) {
            throw new BadRequestException(
                    "Account has already been verified."
            );
        }

        if (user.getStatus() != UserStatus.PENDING) {
            throw new BadRequestException(
                    "Account is not eligible for verification."
            );
        }

        sendVerificationOtp(user);
    }

    //==============  verify otp =============== //
    @Transactional(noRollbackFor = BadRequestException.class)
    public void verifyOtp(String email, String otp) {

        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new BadRequestException(
                                "Invalid or expired verification code."
                        ));

        OtpVerificationEntity verification =
                otpVerificationRepository.findByUser(user)
                        .orElseThrow(() ->
                                new BadRequestException(
                                        "Invalid or expired verification code."
                                ));

        if (verification.isVerified()) {
            throw new BadRequestException(
                    "Account has already been verified."
            );
        }

        if (verification.getExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException(
                    "Verification code has expired."
            );
        }

        if (verification.getAttempts() >= verification.getMaxAttempts()) {
            throw new BadRequestException(
                    "Maximum verification attempts exceeded."
            );
        }

        if (!passwordEncoder.matches(otp, verification.getOtpHash())) {

            verification.setAttempts(verification.getAttempts() + 1);

            otpVerificationRepository.save(verification);

            throw new BadRequestException(
                    "Invalid verification code."
            );
        }

        verification.setVerified(true);
        verification.setVerifiedAt(Instant.now());

        otpVerificationRepository.save(verification);

        user.setStatus(UserStatus.ACTIVE);
    }

}
