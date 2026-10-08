package com.application.e_wallet.auth.service;

import com.application.e_wallet.auth.dto.*;
import com.application.e_wallet.auth.entity.UserSessionEntity;
import com.application.e_wallet.auth.repository.UserSessionRepository;
import com.application.e_wallet.common.event.UserRegisteredEvent;
import com.application.e_wallet.customer.service.CustomerService;
import com.application.e_wallet.permission.entity.PermissionEntity;
import com.application.e_wallet.role.entity.RoleEntity;
import com.application.e_wallet.role.repository.RoleRepository;
import com.application.e_wallet.security.jwt.JwtProperties;
import com.application.e_wallet.security.jwt.JwtService;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.entity.UserStatus;
import com.application.e_wallet.user.repository.UserRepository;
import com.application.e_wallet.common.exception.AuthenticationException;
import com.application.e_wallet.common.exception.DuplicationResourceException;
import com.application.e_wallet.common.validation.PhoneNumberNormalizer;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final CustomerService customerService;
    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final AccountStatusService accountStatusService;
    private final UserSessionRepository userSessionRepository;
    private final RefreshTokenHashService refreshTokenHashService;

    @Transactional
    public RegistrationResponse registerCustomer(CustomerRegistrationRequest request){

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicationResourceException("Email already exists");
        }

        String normalizedPhone = phoneNumberNormalizer.normalize(request.getPhone());

        if (userRepository.existsByPhone(normalizedPhone)) {
            throw new DuplicationResourceException("Phone already exists");
        }

        RoleEntity customerRole = roleRepository
                .findByName("CUSTOMER")
                .orElseThrow(() ->
                        new IllegalStateException(
                                "CUSTOMER role not found"
                        ));

        String middleName = request.getMiddleName() != null ? request.getMiddleName().trim() : "";

        UserEntity user = UserEntity.builder()
                .firstName(request.getFirstName().trim())
                .middleName(middleName)
                .lastName(request.getLastName().trim())
                .email(request.getEmail().trim().toLowerCase())
                .phone(normalizedPhone)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .status(UserStatus.PENDING)
                .build();

        user.getRoles().add(customerRole);

        UserEntity savedUser = userRepository.save(user);  //======== user save in database ======

        customerService.createCustomer(savedUser); //=== Customer account  created ====

        eventPublisher.publishEvent(new UserRegisteredEvent(savedUser.getId(), savedUser.getEmail()));

        return RegistrationResponse.builder()
                .userId(savedUser.getId())
                .message("Customer registered successfully. Verification code has been sent to your email.")
                .status(savedUser.getStatus())
                .build();
    }

    /**
     * LOGIN RESPONSE
     */
    @Transactional
    public LoginResponse login(LoginRequest request) {

        String email = request.getEmail()
                .trim()
                .toLowerCase();

        UserEntity user = userRepository
                .findByEmail(email)
                .orElseThrow(() ->
                        new AuthenticationException(
                                "Invalid email or password"
                        ));

        accountStatusService.validateLoginStatus(user); // check account status

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new AuthenticationException(
                    "Invalid email or password"
            );
        }

        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);

        UserSessionEntity session = UserSessionEntity.builder()
                .user(user)
                .refreshTokenHash(refreshTokenHashService.hash(refreshToken))
                .expiresAt(OffsetDateTime.now().plus(Duration.ofMillis(jwtProperties.getRefreshTokenExpiration())))
                .revoked(false)
                .build();

        userSessionRepository.save(session);

        List<String> roles = user.getRoles().stream()
                .map(RoleEntity::getName)
                .toList();

        List<String> permissions = user.getRoles().stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(PermissionEntity::getName)
                .distinct()
                .toList();

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .roles(roles)
                .permissions(permissions)
                .accessTokenExpiresIn(jwtProperties.getAccessTokenExpiration())
                .refreshTokenExpiresIn(jwtProperties.getRefreshTokenExpiration())
                .build();
    }

    /**
     * ACCOUNT STATUS RESPONSE
     */
    @Transactional
    public AccountStatusResponse getAccountStatus(String email){

        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new AuthenticationException(
                                "User account not found"
                        )
                );

        boolean active = user.getStatus() == UserStatus.ACTIVE;

        return AccountStatusResponse.builder()
                .status(user.getStatus())
                .active(active)
                .message(getStatusMessage(user.getStatus()))
                .build();
    }

    private String getStatusMessage(UserStatus status) {

        return switch (status) {

            case ACTIVE -> "Account is active";
            case PENDING -> "Account is pending";
            case SUSPENDED -> "Account is suspended";
            case BLOCKED -> "Account is blocked";
            case CLOSED -> "Account is closed";
            case DISABLED -> "Account is disabled";
        };
    }


    /**
     * CHANGE PASSWORD RESPONSE
     */
    @Transactional
    public void changePassword(String email, ChangePasswordRequest request){

        UserEntity userEntity = userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new AuthenticationException(
                                "User account not found"
                        )
                );

        accountStatusService.validateLoginStatus(userEntity);

        if (!passwordEncoder.matches( request.getCurrentPassword(), userEntity.getPassword())) {
            throw new AuthenticationException(
                    "Current password is incorrect"
            );
        }

        if (passwordEncoder.matches( request.getNewPassword(), userEntity.getPasswordHash())) {
            throw new AuthenticationException(
                    "New password must be different from current password"
            );
        }

        userEntity.setPasswordHash(
                passwordEncoder.encode(request.getNewPassword())
        );

        userRepository.save(userEntity);

        // Password changed — kill every refresh session so a stolen token
        // can't keep issuing new access tokens.
        userSessionRepository.deleteAllByUserId(userEntity.getId());
    }

    @Transactional
   public void logout(String email, String refreshToken){

        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new AuthenticationException("User account not found"));

        String tokenHash = refreshTokenHashService.hash(refreshToken);

        UserSessionEntity session = userSessionRepository
                .findByRefreshTokenHash(tokenHash)
                .orElseThrow(() ->
                        new AuthenticationException("Invalid refresh token"));

        if (!session.getUser().getId().equals(user.getId())) {
            throw new AuthenticationException("Invalid refresh token");
        }

        if (session.isRevoked()) {
            throw new AuthenticationException("Session is revoked");
        }

        session.setRevoked(true);
        session.setRevokedAt(OffsetDateTime.now());

        userSessionRepository.save(session);

   }
}
