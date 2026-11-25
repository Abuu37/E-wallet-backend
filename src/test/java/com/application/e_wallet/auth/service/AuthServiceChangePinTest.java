package com.application.e_wallet.auth.service;

import com.application.e_wallet.auth.dto.ChangePinRequest;
import com.application.e_wallet.auth.repository.UserSessionRepository;
import com.application.e_wallet.common.exception.AuthenticationException;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.common.validation.PhoneNumberNormalizer;
import com.application.e_wallet.customer.service.CustomerService;
import com.application.e_wallet.notification.NotificationKey;
import com.application.e_wallet.notification.Notifier;
import com.application.e_wallet.otp.repository.OtpVerificationRepository;
import com.application.e_wallet.security.jwt.JwtProperties;
import com.application.e_wallet.security.jwt.JwtService;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.entity.UserStatus;
import com.application.e_wallet.user.repository.UserRepository;
import com.application.e_wallet.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The change-PIN step that agents and staff take on first sign-in with their one-time PIN. */
@ExtendWith(MockitoExtension.class)
class AuthServiceChangePinTest {

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
    @Mock private Notifier notifier;

    @InjectMocks
    private AuthService authService;

    private UUID userId;
    private UserEntity agent;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        agent = UserEntity.builder().id(userId).email("agent@example.com").phone("+255712345678")
                .pinHash("one-time-hash").status(UserStatus.PIN_CHANGE_REQUIRED).build();
    }

    private static ChangePinRequest request(String current, String next) {
        ChangePinRequest request = new ChangePinRequest();
        request.setCurrentPin(current);
        request.setNewPin(next);
        return request;
    }

    @Test
    void theUserIsFoundFromTheTokenIdNeverByEmail() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(agent));
        when(pinEncoder.matches("7391", "one-time-hash")).thenReturn(true);
        when(pinEncoder.matches("5927", "one-time-hash")).thenReturn(false);
        when(pinEncoder.encode("5927")).thenReturn("new-hash");

        authService.changePin(userId, request("7391", "5927"));

        verify(userRepository).findById(userId);
        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    void successActivatesTheAccountSignsEveryDeviceOutAndSendsS3() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(agent));
        when(pinEncoder.matches("7391", "one-time-hash")).thenReturn(true);
        when(pinEncoder.matches("5927", "one-time-hash")).thenReturn(false);
        when(pinEncoder.encode("5927")).thenReturn("new-hash");

        authService.changePin(userId, request("7391", "5927"));

        assertThat(agent.getPinHash()).isEqualTo("new-hash");
        assertThat(agent.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(userSessionRepository).deleteAllByUserId(userId);
        verify(notifier).notify(eq(NotificationKey.S3), any(), any());
    }

    @Test
    void aWrongOneTimePinChangesNothing() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(agent));
        when(pinEncoder.matches("0000", "one-time-hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.changePin(userId, request("0000", "5927")))
                .isInstanceOf(AuthenticationException.class).hasMessageContaining("Current PIN is incorrect");

        assertThat(agent.getStatus()).isEqualTo(UserStatus.PIN_CHANGE_REQUIRED);
        verify(userRepository, never()).save(any());
        verifyNoInteractions(notifier);
    }

    @Test
    void theNewPinCannotBeTheOneTimePin() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(agent));
        when(pinEncoder.matches("7391", "one-time-hash")).thenReturn(true);

        assertThatThrownBy(() -> authService.changePin(userId, request("7391", "7391")))
                .isInstanceOf(AuthenticationException.class).hasMessageContaining("different");

        verify(userRepository, never()).save(any());
    }

    @Test
    void aWeakNewPinIsRefused() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(agent));
        when(pinEncoder.matches("7391", "one-time-hash")).thenReturn(true);
        when(pinEncoder.matches("1234", "one-time-hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.changePin(userId, request("7391", "1234"))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> authService.changePin(userId, request("7391", "1111"))).isInstanceOf(BadRequestException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void anUnknownUserIdIsRejected() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.changePin(userId, request("7391", "5927"))).isInstanceOf(AuthenticationException.class);
    }
}
