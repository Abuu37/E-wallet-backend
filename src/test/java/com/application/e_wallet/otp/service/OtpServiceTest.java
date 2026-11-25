package com.application.e_wallet.otp.service;

import com.application.e_wallet.auth.service.PinResetService;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.common.validation.PhoneNumberNormalizer;
import com.application.e_wallet.notification.Notifier;
import com.application.e_wallet.otp.config.OtpProperties;
import com.application.e_wallet.otp.entity.OtpVerificationEntity;
import com.application.e_wallet.otp.repository.OtpVerificationRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.entity.UserStatus;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OtpServiceTest {

    @Mock private OtpVerificationRepository otpRepository;
    @Mock private OtpGenerator otpGenerator;
    @Mock private OtpProperties otpProperties;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private UserRepository userRepository;
    @Mock private Notifier notifier;
    @Mock private PhoneNumberNormalizer normalizer;
    @Mock private PinResetService pinResetService;

    private OtpService service;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        service = new OtpService(otpRepository, otpGenerator, otpProperties, passwordEncoder, userRepository, notifier, normalizer, pinResetService);
        user = UserEntity.builder().id(UUID.randomUUID()).phone("+255712345678").email("a@example.com").status(UserStatus.AWAITING_PIN).build();
        lenient().when(normalizer.normalize("0712345678")).thenReturn("+255712345678");
        lenient().when(userRepository.findByPhone("+255712345678")).thenReturn(Optional.of(user));
    }

    private OtpVerificationEntity verification(int attempts, long expiresInSeconds) {
        return OtpVerificationEntity.builder().user(user).otpHash("hash").attempts(attempts).maxAttempts(5).verified(false)
                .expiresAt(Instant.now().plusSeconds(expiresInSeconds)).build();
    }

    @Test
    void aWrongCodeIsRejectedNotSwallowed() {
        OtpVerificationEntity v = verification(0, 300);
        lenient().when(otpRepository.findByUser(user)).thenReturn(Optional.of(v));
        lenient().when(passwordEncoder.matches("000000", "hash")).thenReturn(false);

        assertThatThrownBy(() -> service.verifyOtp("0712345678", "000000"))
                .isInstanceOf(BadRequestException.class).hasMessage("Invalid verification code.");
        assertThat(v.getAttempts()).isEqualTo(1);
        assertThat(v.isVerified()).isFalse();
    }

    @Test
    void anExpiredCodeIsRejected() {
        lenient().when(otpRepository.findByUser(user)).thenReturn(Optional.of(verification(0, -1)));

        assertThatThrownBy(() -> service.verifyOtp("0712345678", "482913"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("expired");
    }

    @Test
    void fiveWrongTriesLockTheCode() {
        lenient().when(otpRepository.findByUser(user)).thenReturn(Optional.of(verification(5, 300)));

        assertThatThrownBy(() -> service.verifyOtp("0712345678", "482913"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Maximum");
    }

    @Test
    void theRightCodeVerifiesTheNumber() {
        OtpVerificationEntity v = verification(0, 300);
        lenient().when(otpRepository.findByUser(user)).thenReturn(Optional.of(v));
        lenient().when(passwordEncoder.matches("482913", "hash")).thenReturn(true);

        service.verifyOtp("0712345678", "482913");

        assertThat(v.isVerified()).isTrue();
        assertThat(v.getVerifiedAt()).isNotNull();
    }

    @Test
    void anUnknownNumberGetsTheSameAnswerAsAWrongCode() {
        lenient().when(normalizer.normalize("0700000000")).thenReturn("+255700000000");
        lenient().when(userRepository.findByPhone("+255700000000")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyOtp("0700000000", "482913"))
                .isInstanceOf(BadRequestException.class).hasMessage("Invalid or expired verification code.");
    }

    @Test
    void anActiveAccountIsCheckingAPinResetCodeInstead() {
        user.setStatus(UserStatus.ACTIVE);

        service.verifyOtp("0712345678", "482913");

        verify(pinResetService).verifyResetCode("0712345678", "482913");
        verifyNoInteractions(otpRepository);
    }
}
