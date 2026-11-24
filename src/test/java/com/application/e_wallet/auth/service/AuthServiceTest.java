package com.application.e_wallet.auth.service;

import com.application.e_wallet.auth.dto.SetPinRequest;
import com.application.e_wallet.auth.dto.UserProfileResponse;
import com.application.e_wallet.auth.repository.UserSessionRepository;
import com.application.e_wallet.common.validation.PhoneNumberNormalizer;
import com.application.e_wallet.customer.entity.CustomerEntity;
import com.application.e_wallet.customer.entity.CustomerStatus;
import com.application.e_wallet.customer.service.CustomerService;
import com.application.e_wallet.otp.entity.OtpVerificationEntity;
import com.application.e_wallet.otp.repository.OtpVerificationRepository;
import com.application.e_wallet.otp.service.EmailService;
import com.application.e_wallet.security.jwt.JwtProperties;
import com.application.e_wallet.security.jwt.JwtService;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.entity.UserStatus;
import com.application.e_wallet.user.repository.UserRepository;
import com.application.e_wallet.wallet.entity.WalletEntity;
import com.application.e_wallet.wallet.service.WalletService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

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
    @Mock private EmailService emailService;

    @InjectMocks
    private AuthService authService;

    @Test
    void setPinResponseReadsNamesFromCustomerEntityNotUser() {
        SetPinRequest request = new SetPinRequest();
        request.setEmail("jane.doe@example.com");
        request.setPin("4829");
        request.setConfirmPin("4829");

        UUID userId = UUID.randomUUID();
        UserEntity user = UserEntity.builder()
                .id(userId)
                .email("jane.doe@example.com")
                .phone("+255712345678")
                .status(UserStatus.AWAITING_PIN)
                .build();

        OtpVerificationEntity verification = OtpVerificationEntity.builder()
                .user(user)
                .verifiedAt(Instant.now())
                .build();

        CustomerEntity customer = CustomerEntity.builder()
                .user(user)
                .status(CustomerStatus.ACTIVE)
                .firstName("Jane")
                .middleName("M")
                .lastName("Doe")
                .build();

        when(userRepository.findByEmail("jane.doe@example.com")).thenReturn(Optional.of(user));
        when(otpVerificationRepository.findByUser(user)).thenReturn(Optional.of(verification));
        when(pinEncoder.encode(anyString())).thenReturn("hashed-pin");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(customerService.activateCustomer(userId)).thenReturn(customer);
        when(walletService.createWallet(customer)).thenReturn(WalletEntity.builder().build());

        UserProfileResponse response = authService.setPin(request);

        assertThat(response.getFirstName()).isEqualTo("Jane");
        assertThat(response.getMiddleName()).isEqualTo("M");
        assertThat(response.getLastName()).isEqualTo("Doe");
    }
}
