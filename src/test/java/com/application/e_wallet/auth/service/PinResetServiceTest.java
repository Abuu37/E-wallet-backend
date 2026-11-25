package com.application.e_wallet.auth.service;

import com.application.e_wallet.auth.config.PinResetProperties;
import com.application.e_wallet.auth.entity.PinResetTokenEntity;
import com.application.e_wallet.auth.repository.PinResetTokenRepository;
import com.application.e_wallet.auth.repository.UserSessionRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.common.validation.PhoneNumberNormalizer;
import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.customer.repository.CustomerRepository;
import com.application.e_wallet.notification.NotificationKey;
import com.application.e_wallet.notification.Notifier;
import com.application.e_wallet.otp.service.OtpGenerator;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PinResetServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private PinResetTokenRepository tokenRepository;
    @Mock private UserSessionRepository sessionRepository;
    @Mock private OtpGenerator otpGenerator;
    @Mock private PasswordEncoder otpEncoder;
    @Mock private PasswordEncoder pinEncoder;
    @Mock private PhoneNumberNormalizer normalizer;
    @Mock private Notifier notifier;

    private PinResetService service;
    private UserEntity amina;

    @BeforeEach
    void setUp() {
        PinResetProperties props = new PinResetProperties();
        props.setLength(6);
        props.setExpirationMinutes(5);
        props.setMaxAttempts(5);
        props.setResendCooldownSeconds(60);
        service = new PinResetService(userRepository, customerRepository, tokenRepository, sessionRepository,
                otpGenerator, props, otpEncoder, pinEncoder, normalizer, notifier);

        amina = UserEntity.builder().id(UUID.randomUUID()).email("amina@example.com").phone("+255712345678").pinHash("old-pin-hash").build();
        lenient().when(normalizer.normalize("0712345678")).thenReturn("+255712345678");
        lenient().when(userRepository.findByPhone("+255712345678")).thenReturn(Optional.of(amina));
        lenient().when(customerRepository.findByUserId(amina.getId()))
                .thenReturn(Optional.of(CustomerEntity.builder().nidaNumber("19900412-12345-00001-23").build()));
        lenient().when(otpGenerator.generate(6)).thenReturn("482913");
        lenient().when(otpEncoder.encode("482913")).thenReturn("otp-hash");
    }

    private PinResetTokenEntity token(int attempts) {
        return PinResetTokenEntity.builder().user(amina).otpHash("otp-hash").attempts(attempts).maxAttempts(5).used(false)
                .expiresAt(Instant.now().plusSeconds(300)).build();
    }

    // ---- step 1: phone + last 4 NIDA digits

    @Test
    void theRightLastFourDigitsSendTheCode() {
        when(tokenRepository.findByUser(amina)).thenReturn(Optional.empty());

        service.forgotPin("0712345678", "0123");

        verify(tokenRepository).save(any(PinResetTokenEntity.class));
        verify(notifier).notify(eq(NotificationKey.S2), any(), any());
    }

    @Test
    void aWrongNidaSendsNothingAndSaysNothing() {
        service.forgotPin("0712345678", "9999");

        verifyNoInteractions(notifier);
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void anUnknownPhoneSendsNothingAndSaysNothing() {
        when(normalizer.normalize("0700000000")).thenReturn("+255700000000");
        when(userRepository.findByPhone("+255700000000")).thenReturn(Optional.empty());

        service.forgotPin("0700000000", "0123");

        verifyNoInteractions(notifier);
    }

    @Test
    void anAgentOrStaffMemberHasNoNidaSoSelfServiceDoesNothing() {
        when(customerRepository.findByUserId(amina.getId())).thenReturn(Optional.empty());

        service.forgotPin("0712345678", "0123");

        verifyNoInteractions(notifier);
    }

    @Test
    void aSecondRequestInsideTheCooldownIsSilent() {
        PinResetTokenEntity recent = token(0);
        recent.setCreatedAt(Instant.now().minusSeconds(10));
        when(tokenRepository.findByUser(amina)).thenReturn(Optional.of(recent));

        service.forgotPin("0712345678", "0123");

        verifyNoInteractions(notifier);
    }

    // ---- step 2: check the code

    @Test
    void aWrongCodeIsRejectedAndCostsOneTry() {
        PinResetTokenEntity t = token(1);
        when(tokenRepository.findByUser(amina)).thenReturn(Optional.of(t));
        when(otpEncoder.matches("000000", "otp-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.verifyResetCode("0712345678", "000000"))
                .isInstanceOf(BadRequestException.class).hasMessage("Invalid reset code.");
        assertThat(t.getAttempts()).isEqualTo(2);
    }

    @Test
    void afterFiveWrongTriesTheCodeIsDead() {
        when(tokenRepository.findByUser(amina)).thenReturn(Optional.of(token(5)));

        assertThatThrownBy(() -> service.verifyResetCode("0712345678", "482913"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Maximum");
    }

    @Test
    void aRightCodeIsAccepted() {
        when(tokenRepository.findByUser(amina)).thenReturn(Optional.of(token(0)));
        when(otpEncoder.matches("482913", "otp-hash")).thenReturn(true);

        service.verifyResetCode("0712345678", "482913");
    }

    // ---- step 3: new PIN

    @Test
    void resetSetsThePinSignsEveryDeviceOutAndTellsThePerson() {
        PinResetTokenEntity t = token(0);
        when(tokenRepository.findByUser(amina)).thenReturn(Optional.of(t));
        when(otpEncoder.matches("482913", "otp-hash")).thenReturn(true);
        when(pinEncoder.matches("5927", "old-pin-hash")).thenReturn(false);
        when(pinEncoder.encode("5927")).thenReturn("new-pin-hash");

        service.resetPin("0712345678", "482913", "5927");

        assertThat(amina.getPinHash()).isEqualTo("new-pin-hash");
        assertThat(t.isUsed()).isTrue();
        verify(sessionRepository).deleteAllByUserId(amina.getId());
        verify(notifier).notify(eq(NotificationKey.S3), any(), any());
    }

    @Test
    void resetRefusesAWeakPinAndTouchesNothing() {
        assertThatThrownBy(() -> service.resetPin("0712345678", "482913", "1234")).isInstanceOf(BadRequestException.class);

        verifyNoInteractions(sessionRepository, notifier);
    }

    @Test
    void resetRefusesTheCurrentPin() {
        when(tokenRepository.findByUser(amina)).thenReturn(Optional.of(token(0)));
        when(otpEncoder.matches("482913", "otp-hash")).thenReturn(true);
        when(pinEncoder.matches("5927", "old-pin-hash")).thenReturn(true);

        assertThatThrownBy(() -> service.resetPin("0712345678", "482913", "5927")).isInstanceOf(BadRequestException.class);

        verifyNoInteractions(sessionRepository);
    }

    @Test
    void aCodeCanOnlyBeUsedOnce() {
        PinResetTokenEntity t = token(0);
        t.setUsed(true);
        when(tokenRepository.findByUser(amina)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.resetPin("0712345678", "482913", "5927"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("already been used");
    }

    @Test
    void anUnknownPhoneGetsTheSameAnswerAsAWrongCode() {
        when(normalizer.normalize(anyString())).thenReturn("+255700000000");
        when(userRepository.findByPhone("+255700000000")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPin("0700000000", "482913", "5927"))
                .isInstanceOf(BadRequestException.class).hasMessage("Invalid or expired reset code.");
    }
}
