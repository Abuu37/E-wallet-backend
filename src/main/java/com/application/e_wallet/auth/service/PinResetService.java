package com.application.e_wallet.auth.service;

import com.application.e_wallet.auth.config.PinResetProperties;
import com.application.e_wallet.auth.entity.PinResetTokenEntity;
import com.application.e_wallet.auth.repository.PinResetTokenRepository;
import com.application.e_wallet.auth.repository.UserSessionRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.common.validation.PhoneNumberNormalizer;
import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.common.validation.PinPolicy;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.notification.NotificationKey;
import com.application.e_wallet.notification.NotificationRecipient;
import com.application.e_wallet.notification.Notifier;
import com.application.e_wallet.otp.service.OtpGenerator;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Forgot PIN for customers: phone + last 4 digits of the NIDA number, then a 6-digit code by email to the
 * address on file. Agents and staff have no NIDA on file, so an administrator resets their PIN instead.
 * Nothing here ever says whether a phone or NIDA pair exists.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PinResetService {

    private static final String INVALID_CODE = "Invalid or expired reset code.";

    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final PinResetTokenRepository pinResetTokenRepository;
    private final UserSessionRepository userSessionRepository;
    private final OtpGenerator otpGenerator;
    private final PinResetProperties pinResetProperties;
    private final PasswordEncoder passwordEncoder; // hashes/verifies the OTP
    private final PasswordEncoder pinEncoder; // hashes/verifies the PIN
    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final Notifier notifier;

    //======== Always called from the controller: the answer never says whether the pair matched ========
    @Transactional
    public void forgotPin(String phone, String nidaLast4) {
        Optional<UserEntity> match = findCustomerUser(phone, nidaLast4);
        if (match.isEmpty()) {
            // Silent on purpose. The controller gives the same answer whether or not this matched.
            return;
        }
        UserEntity user = match.get();

        Optional<PinResetTokenEntity> existing = pinResetTokenRepository.findByUser(user);
        if (existing.isPresent()) {
            Instant cooldownEnd = existing.get().getCreatedAt()
                    .plusSeconds(pinResetProperties.getResendCooldownSeconds());
            if (cooldownEnd.isAfter(Instant.now())) {
                // Within the cooldown: stay silent instead of saying "please wait", which would leak that the account exists.
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

        notifier.notify(NotificationKey.S2, NotificationRecipient.of(user),
                Map.of("code", otp, "minutes", String.valueOf(pinResetProperties.getExpirationMinutes())));
    }

    /** Step 2 of the screen: is this the right code? A wrong code costs one of the 5 tries. The PIN is not touched. */
    @Transactional(noRollbackFor = BadRequestException.class)
    public void verifyResetCode(String phone, String otp) {
        UserEntity user = findUserByPhone(phone).orElseThrow(() -> new BadRequestException(INVALID_CODE));
        checkCode(user, otp);
    }

    @Transactional(noRollbackFor = BadRequestException.class)
    public void resetPin(String phone, String otp, String newPin) {
        if (PinPolicy.isWeak(newPin)) {
            throw new BadRequestException(PinPolicy.WEAK_PIN_MESSAGE);
        }

        UserEntity user = findUserByPhone(phone).orElseThrow(() -> new BadRequestException(INVALID_CODE));
        PinResetTokenEntity token = checkCode(user, otp);

        if (pinEncoder.matches(newPin, user.getPinHash())) {
            throw new BadRequestException(
                    "New PIN must be different from current PIN."
            );
        }

        token.setUsed(true);
        token.setUsedAt(Instant.now());
        pinResetTokenRepository.save(token);

        user.setFailedPinAttempts(0);
        user.setUnlockRequiresOtp(false); // lockedUntil is kept: the 30-minute lock still has to run out
        user.setPinHash(pinEncoder.encode(newPin));
        userRepository.save(user);

        // Every device is signed out, then the person is told (message S3, after the commit).
        userSessionRepository.deleteAllByUserId(user.getId());
        notifier.notify(NotificationKey.S3, NotificationRecipient.of(user), Map.of());
    }

    /** Same checks for the verify step and the reset step; a wrong code counts as an attempt in both. */
    private PinResetTokenEntity checkCode(UserEntity user, String otp) {
        PinResetTokenEntity token = pinResetTokenRepository.findByUser(user)
                .orElseThrow(() -> new BadRequestException(INVALID_CODE));

        if (token.isUsed()) {
            throw new BadRequestException("This reset code has already been used.");
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException("Reset code has expired.");
        }
        if (token.getAttempts() >= token.getMaxAttempts()) {
            throw new BadRequestException("Maximum reset attempts exceeded. Please request a new code.");
        }
        if (!passwordEncoder.matches(otp, token.getOtpHash())) {
            token.setAttempts(token.getAttempts() + 1);
            pinResetTokenRepository.save(token);
            throw new BadRequestException("Invalid reset code.");
        }
        return token;
    }

    private Optional<UserEntity> findUserByPhone(String phone) {
        try {
            return userRepository.findByPhone(phoneNumberNormalizer.normalize(phone));
        } catch (RuntimeException invalidNumber) {
            return Optional.empty();
        }
    }

    /** The customer behind this phone, if the last 4 NIDA digits match. Anyone else gets nothing. */
    private Optional<UserEntity> findCustomerUser(String phone, String nidaLast4) {
        return findUserByPhone(phone).filter(user -> {
            Optional<CustomerEntity> customer = customerRepository.findByUserId(user.getId());
            if (customer.isEmpty() || customer.get().getNidaNumber() == null || nidaLast4 == null) {
                return false;
            }
            String digits = customer.get().getNidaNumber().replaceAll("\\D", "");
            return digits.length() >= 4 && digits.endsWith(nidaLast4.trim());
        });
    }
}
