package com.application.e_wallet.auth.service;

import com.application.e_wallet.auth.dto.*;
import com.application.e_wallet.auth.entity.UserSessionEntity;
import com.application.e_wallet.auth.repository.UserSessionRepository;
import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.customer.service.CustomerService;
import com.application.e_wallet.permission.entity.PermissionEntity;
import com.application.e_wallet.security.jwt.JwtProperties;
import com.application.e_wallet.security.jwt.JwtService;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.entity.UserStatus;
import com.application.e_wallet.user.repository.UserRepository;
import com.application.e_wallet.common.exception.AuthenticationException;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.common.validation.PinPolicy;
import com.application.e_wallet.common.validation.PhoneNumberNormalizer;
import com.application.e_wallet.otp.entity.OtpVerificationEntity;
import com.application.e_wallet.otp.repository.OtpVerificationRepository;
import com.application.e_wallet.otp.service.EmailService;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.service.WalletService;
import io.jsonwebtoken.JwtException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder pinEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final CustomerService customerService;
    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final AccountStatusService accountStatusService;
    private final UserSessionRepository userSessionRepository;
    private final RefreshTokenHashService refreshTokenHashService;
    private final WalletService walletService;
    private final OtpVerificationRepository otpVerificationRepository;
    private final EmailService emailService;

    // How long after OTP verification the user has to call set-pin before they must re-verify.
    private static final long PIN_SETUP_WINDOW_MINUTES = 15;


    /**
     * SET PIN — completes registration once the email has been OTP-verified.
     */
    @Transactional(dontRollbackOn = BadRequestException.class)
    public UserProfileResponse setPin(SetPinRequest request) {

        String email = request.getEmail().trim().toLowerCase();
        if (PinPolicy.isWeak(request.getPin())) {
            throw new BadRequestException(PinPolicy.WEAK_PIN_MESSAGE);
        }
        String genericErrorMessage = "PIN cannot be set for this account.";

        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    log.warn("========== set-pin rejected: no account found for email={} =========", email);
                    return new BadRequestException(genericErrorMessage);
                });

        if (user.getStatus() != UserStatus.AWAITING_PIN) {
            log.warn("======= set-pin rejected: email={} has status={}, expected AWAITING_PIN =======", email, user.getStatus());
            throw new BadRequestException(
                    genericErrorMessage
            );
        }

        OtpVerificationEntity verification = otpVerificationRepository.findByUser(user)
                .orElseThrow(() -> {
                    log.warn("set-pin rejected: no OTP verification record for email={}", email);
                    return new BadRequestException(
                            genericErrorMessage
                    );
                });

        boolean windowExpired = verification.getVerifiedAt() == null
                || verification.getVerifiedAt().isBefore(Instant.now().minus(PIN_SETUP_WINDOW_MINUTES, ChronoUnit.MINUTES));

        if (windowExpired) {
            // Self-healing: drop back to PENDING so resend-otp is reachable again.
            user.setStatus(UserStatus.AWAITING_PIN);
            userRepository.save(user);

            throw new BadRequestException(
                    "PIN setup has expired. Please request a new verification code."
            );
        }

        user.setPinHash(pinEncoder.encode(request.getPin()));
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);
        CustomerEntity customer = customerService.activateCustomer(user.getId());
        WalletEntity wallet = walletService.createWallet(customer);

        emailService.sendPinSetConfirmation(user.getEmail()); // email pin confirmation

        return UserProfileResponse.builder()
                .customerId(customer.getId())
                .firstName(customer.getFirstName())
                .middleName(customer.getMiddleName())
                .lastName(customer.getLastName())
                .phoneNumber(user.getPhone())
                .email(user.getEmail())
                .nidaNumber(customer.getNidaNumber())
                .dateOfBirth(customer.getDateOfBirth())
                .address(customer.getAddress())
                .status(user.getStatus())
                .wallet(UserProfileResponse.WalletSummary.builder()
                        .accountNumber(wallet.getAccountNumber())
                        .walletType(wallet.getWalletType())
                        .balance(wallet.getBalance())
                        .currency(wallet.getCurrency())
                        .build())
                .createdAt(user.getCreatedAt())
                .build();
    }

    private static final int MAX_FAILED_PIN_ATTEMPTS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(30);

    /**
     * LOGIN. 5 wrong PINs lock the account for 30 minutes; after that the PIN must be
     * reset with an emailed code (the forgot-pin flow) before sign-in works again.
     * AuthenticationException must NOT roll back: the failed-attempt counter has to persist.
     */
    @Transactional(dontRollbackOn = AuthenticationException.class)
    public AuthResult login(LoginRequest request) {

        String normalizedPhone = phoneNumberNormalizer.normalize(request.getPhone());

        UserEntity user = userRepository
                .findByPhone(normalizedPhone)
                .orElseThrow(() -> new AuthenticationException("Invalid credentials"));

        accountStatusService.validateLoginStatus(user); // check account status

        Instant now = Instant.now();
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            throw new AuthenticationException("Account is locked after too many wrong PINs. Try again later.");
        }
        if (user.isUnlockRequiresOtp()) {
            throw new AuthenticationException(
                    "Your account needs a verification code to unlock. Use Forgot PIN to reset your PIN.");
        }

        if (!pinEncoder.matches(request.getPin(), user.getPinHash())) {
            user.setFailedPinAttempts(user.getFailedPinAttempts() + 1);
            if (user.getFailedPinAttempts() >= MAX_FAILED_PIN_ATTEMPTS) {
                user.setLockedUntil(now.plus(LOCK_DURATION));
                user.setUnlockRequiresOtp(true);
                user.setFailedPinAttempts(0);
                log.warn("Account locked after repeated wrong PINs userId={}", user.getId());
            }
            userRepository.save(user);
            throw new AuthenticationException("Invalid credentials");
        }

        user.setFailedPinAttempts(0);
        user.setLastLoginAt(now);
        userRepository.save(user);

        String refreshToken = issueSession(user, UUID.randomUUID());

        return new AuthResult(buildLoginResponse(user), refreshToken);
    }

    /**
     * REFRESH TOKEN — rotates on every use inside one session family. Presenting an
     * already-rotated (revoked) token is treated as theft and revokes the whole family.
     * The exception must not roll the revocation back.
     */
    @Transactional(dontRollbackOn = AuthenticationException.class)
    public AuthResult refreshToken(String refreshToken) {

        String invalidTokenMessage = "Invalid refresh token";

        if (refreshToken == null || refreshToken.isBlank()) {
            throw new AuthenticationException(invalidTokenMessage);
        }

        try {
            if (!JwtService.REFRESH_TOKEN_TYPE.equals(jwtService.extractTokenType(refreshToken))) {
                throw new AuthenticationException(invalidTokenMessage);
            }
        } catch (JwtException | IllegalArgumentException exception) {
            throw new AuthenticationException(invalidTokenMessage);
        }

        String tokenHash = refreshTokenHashService.hash(refreshToken);

        UserSessionEntity session = userSessionRepository.findByRefreshTokenHash(tokenHash)
                .orElseThrow(() -> new AuthenticationException(invalidTokenMessage));

        if (session.isRevoked()) {
            userSessionRepository.revokeFamily(session.getFamilyId(), OffsetDateTime.now());
            log.warn("Refresh token reuse detected; session family revoked userId={}", session.getUser().getId());
            throw new AuthenticationException("Session is no longer valid. Please log in again.");
        }

        if (session.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new AuthenticationException("Refresh token has expired. Please log in again.");
        }

        UserEntity user = session.getUser();
        accountStatusService.validateLoginStatus(user);

        session.setRevoked(true);
        session.setRevokedAt(OffsetDateTime.now());
        userSessionRepository.save(session);

        String newRefreshToken = issueSession(user, session.getFamilyId());

        return new AuthResult(buildLoginResponse(user), newRefreshToken);
    }

    private String issueSession(UserEntity user, UUID familyId) {
        String refreshToken = jwtService.generateRefreshToken(user);

        UserSessionEntity session = UserSessionEntity.builder()
                .user(user)
                .familyId(familyId)
                .refreshTokenHash(refreshTokenHashService.hash(refreshToken))
                .expiresAt(OffsetDateTime.now().plus(Duration.ofMillis(jwtProperties.getRefreshTokenExpiration())))
                .revoked(false)
                .build();

        userSessionRepository.save(session);

        return refreshToken;
    }

    private LoginResponse buildLoginResponse(UserEntity user) {
        String accessToken = jwtService.generateAccessToken(user);

        List<String> roles = List.of(user.getRole().getName());

        List<String> permissions = user.getRole().getPermissions().stream()
                .map(PermissionEntity::getName)
                .distinct()
                .toList();

        return LoginResponse.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .roles(roles)
                .permissions(permissions)
                .accessTokenExpiresIn(jwtProperties.getAccessTokenExpiration())
                .refreshTokenExpiresIn(jwtProperties.getRefreshTokenExpiration())
                .mustChangePin(user.getStatus() == UserStatus.PIN_CHANGE_REQUIRED)
                .build();
    }

    /**
     * ACCOUNT STATUS RESPONSE
     */
    @Transactional
    public AccountStatusResponse getAccountStatus(String email){

        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new AuthenticationException("User account not found"));

        boolean active = user.getStatus() == UserStatus.ACTIVE;

        return AccountStatusResponse.builder()
                .status(user.getStatus())
                .active(active)
                .build();
    }

    /**
     * CHANGE PIN RESPONSE
     */
    @Transactional
    public void changePin(String email, ChangePinRequest request){

        UserEntity userEntity = userRepository.findByEmail(email)
                .orElseThrow(() -> new AuthenticationException("User account not found"));

        accountStatusService.validateLoginStatus(userEntity);

        if (!pinEncoder.matches( request.getCurrentPin(), userEntity.getPassword())) {
            throw new AuthenticationException(
                    "Current PIN is incorrect"
            );
        }

        if (pinEncoder.matches( request.getNewPin(), userEntity.getPinHash())) {
            throw new AuthenticationException(
                    "New PIN must be different from current PIN"
            );
        }
        if (PinPolicy.isWeak(request.getNewPin())) {
            throw new BadRequestException(PinPolicy.WEAK_PIN_MESSAGE);
        }
        userEntity.setPinHash( pinEncoder.encode(request.getNewPin()));

        if (userEntity.getStatus() == UserStatus.PIN_CHANGE_REQUIRED) {
            userEntity.setStatus(UserStatus.ACTIVE);
        }
        userRepository.save(userEntity);
        userSessionRepository.deleteAllByUserId(userEntity.getId());
    }

    //================= logout ====================
    /** Ends the whole session family for the presented cookie token. Unknown tokens are ignored. */
    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        userSessionRepository.findByRefreshTokenHash(refreshTokenHashService.hash(refreshToken))
                .ifPresent(session -> userSessionRepository.revokeFamily(session.getFamilyId(), OffsetDateTime.now()));
    }
}
