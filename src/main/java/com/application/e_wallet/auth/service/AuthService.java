package com.application.e_wallet.auth.service;

import com.application.e_wallet.auth.dto.CustomerRegistrationRequest;
import com.application.e_wallet.auth.dto.LoginRequest;
import com.application.e_wallet.auth.dto.LoginResponse;
import com.application.e_wallet.auth.dto.RegistrationResponse;
import com.application.e_wallet.common.event.UserRegisteredEvent;
import com.application.e_wallet.customer.service.CustomerService;
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

import java.util.stream.Collectors;


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

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AuthenticationException(
                    "Account is not active"
            );
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new AuthenticationException(
                    "Invalid email or password"
            );
        }

        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .accessTokenExpiresIn(jwtProperties.getAccessTokenExpiration())
                .refreshTokenExpiresIn(jwtProperties.getRefreshTokenExpiration())
                .build();
    }
}
