package com.application.e_wallet.auth.service;

import com.application.e_wallet.auth.dto.AuthResult;
import com.application.e_wallet.auth.dto.LoginRequest;
import com.application.e_wallet.auth.dto.SetPinRequest;
import com.application.e_wallet.auth.entity.UserSessionEntity;
import com.application.e_wallet.auth.repository.UserSessionRepository;
import com.application.e_wallet.common.exception.AuthenticationException;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.common.validation.PhoneNumberNormalizer;
import com.application.e_wallet.customer.service.CustomerService;
import com.application.e_wallet.otp.repository.OtpVerificationRepository;
import com.application.e_wallet.role.entity.RoleEntity;
import com.application.e_wallet.security.jwt.JwtProperties;
import com.application.e_wallet.security.jwt.JwtService;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.entity.UserStatus;
import com.application.e_wallet.user.repository.UserRepository;
import com.application.e_wallet.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceSecurityTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder pinEncoder;
    @Mock private JwtService jwtService;
    @Mock private JwtProperties jwtProperties;
    @Mock private CustomerService customerService;
    @Mock private PhoneNumberNormalizer phoneNumberNormalizer;
    @Mock private AccountStatusService accountStatusService;
    @Mock private UserSessionRepository userSessionRepository;
    @Mock private RefreshTokenHashService refreshTokenHashService;
    @Mock private WalletService walletService;
    @Mock private OtpVerificationRepository otpVerificationRepository;
    @Mock private com.application.e_wallet.notification.Notifier notifier;

    @InjectMocks private AuthService authService;

    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = UserEntity.builder()
                .id(UUID.randomUUID())
                .email("a@example.com")
                .phone("+255712345678")
                .pinHash("hash")
                .status(UserStatus.ACTIVE)
                .role(RoleEntity.builder().name("CUSTOMER").build())
                .build();
        when(phoneNumberNormalizer.normalize(anyString())).thenReturn("+255712345678");
        when(userRepository.findByPhone("+255712345678")).thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken(any())).thenReturn("access");
        when(jwtService.generateRefreshToken(any())).thenReturn("refresh");
        when(refreshTokenHashService.hash(anyString())).thenAnswer(i -> "h-" + i.getArgument(0));
        when(jwtProperties.getRefreshTokenExpiration()).thenReturn(604_800_000L);
        when(jwtProperties.getAccessTokenExpiration()).thenReturn(900_000L);
    }

    private LoginRequest loginRequest(String pin) {
        LoginRequest r = new LoginRequest();
        r.setPhone("0712345678");
        r.setPin(pin);
        return r;
    }

    @Test
    void wrongPinIncrementsFailedAttemptsWithoutLocking() {
        when(pinEncoder.matches("4829", "hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(loginRequest("4829")))
                .isInstanceOf(AuthenticationException.class);

        assertThat(user.getFailedPinAttempts()).isEqualTo(1);
        assertThat(user.getLockedUntil()).isNull();
        verify(userRepository).save(user);
    }

    @Test
    void fifthWrongPinLocksTheAccountForThirtyMinutesAndRequiresOtp() {
        user.setFailedPinAttempts(4);
        when(pinEncoder.matches("4829", "hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(loginRequest("4829")))
                .isInstanceOf(AuthenticationException.class);

        assertThat(user.getLockedUntil()).isBetween(
                Instant.now().plus(29, ChronoUnit.MINUTES),
                Instant.now().plus(31, ChronoUnit.MINUTES));
        assertThat(user.isUnlockRequiresOtp()).isTrue();
        assertThat(user.getFailedPinAttempts()).isZero();
        verify(userRepository).save(user);
    }

    @Test
    void lockedAccountRejectsEvenTheCorrectPin() {
        user.setLockedUntil(Instant.now().plus(10, ChronoUnit.MINUTES));
        user.setUnlockRequiresOtp(true);
        when(pinEncoder.matches(anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> authService.login(loginRequest("4829")))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("locked");

        verify(pinEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void afterTheLockExpiresAnOtpResetIsStillRequired() {
        user.setLockedUntil(Instant.now().minus(1, ChronoUnit.MINUTES));
        user.setUnlockRequiresOtp(true);

        assertThatThrownBy(() -> authService.login(loginRequest("4829")))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("verification code");

        verify(pinEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void successfulLoginResetsTheCounterAndStartsANewSessionFamily() {
        user.setFailedPinAttempts(3);
        when(pinEncoder.matches("4829", "hash")).thenReturn(true);

        AuthResult result = authService.login(loginRequest("4829"));

        assertThat(user.getFailedPinAttempts()).isZero();
        assertThat(result.refreshToken()).isEqualTo("refresh");
        assertThat(result.body().getAccessToken()).isEqualTo("access");
        ArgumentCaptor<UserSessionEntity> captor = ArgumentCaptor.forClass(UserSessionEntity.class);
        verify(userSessionRepository).save(captor.capture());
        assertThat(captor.getValue().getFamilyId()).isNotNull();
    }

    @Test
    void refreshRotatesWithinTheSameFamily() {
        UUID family = UUID.randomUUID();
        UserSessionEntity old = UserSessionEntity.builder()
                .user(user).familyId(family).refreshTokenHash("h-old")
                .expiresAt(OffsetDateTime.now().plusDays(1)).revoked(false).build();
        when(jwtService.extractTokenType("old")).thenReturn(JwtService.REFRESH_TOKEN_TYPE);
        when(userSessionRepository.findByRefreshTokenHash("h-old")).thenReturn(Optional.of(old));

        AuthResult result = authService.refreshToken("old");

        assertThat(old.isRevoked()).isTrue();
        ArgumentCaptor<UserSessionEntity> captor = ArgumentCaptor.forClass(UserSessionEntity.class);
        verify(userSessionRepository, atLeastOnce()).save(captor.capture());
        UserSessionEntity created = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(created.getFamilyId()).isEqualTo(family);
        assertThat(result.refreshToken()).isEqualTo("refresh");
    }

    @Test
    void reusingARotatedTokenRevokesTheWholeFamily() {
        UUID family = UUID.randomUUID();
        UserSessionEntity old = UserSessionEntity.builder()
                .user(user).familyId(family).refreshTokenHash("h-old")
                .expiresAt(OffsetDateTime.now().plusDays(1)).revoked(true).build();
        when(jwtService.extractTokenType("old")).thenReturn(JwtService.REFRESH_TOKEN_TYPE);
        when(userSessionRepository.findByRefreshTokenHash("h-old")).thenReturn(Optional.of(old));

        assertThatThrownBy(() -> authService.refreshToken("old"))
                .isInstanceOf(AuthenticationException.class);

        verify(userSessionRepository).revokeFamily(eq(family), any());
        verify(userSessionRepository, never()).deleteAllByUserId(any());
    }

    @Test
    void setPinRejectsWeakPins() {
        SetPinRequest request = new SetPinRequest();
        request.setEmail("a@example.com");
        request.setPin("1234");
        request.setConfirmPin("1234");

        assertThatThrownBy(() -> authService.setPin(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("predictable");
    }
}
