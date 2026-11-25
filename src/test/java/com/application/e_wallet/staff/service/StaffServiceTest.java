package com.application.e_wallet.staff.service;

import com.application.e_wallet.auth.repository.UserSessionRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.common.exception.DuplicationResourceException;
import com.application.e_wallet.common.validation.PhoneNumberNormalizer;
import com.application.e_wallet.otp.service.OtpGenerator;
import com.application.e_wallet.role.entity.RoleEntity;
import com.application.e_wallet.role.repository.RoleRepository;
import com.application.e_wallet.staff.dto.StaffCreationRequest;
import com.application.e_wallet.staff.dto.StaffOnboardResponse;
import com.application.e_wallet.staff.dto.StaffUpdateRequest;
import com.application.e_wallet.staff.dto.StaffUpdateResponse;
import com.application.e_wallet.staff.entity.StaffEntity;
import com.application.e_wallet.staff.entity.StaffStatus;
import com.application.e_wallet.staff.repository.StaffRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.entity.UserStatus;
import com.application.e_wallet.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaffServiceTest {

    @Mock private StaffRepository staffRepository;
    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private UserSessionRepository userSessionRepository;
    @Mock private PhoneNumberNormalizer phoneNumberNormalizer;
    @Mock private OtpGenerator otpGenerator;
    @Mock private PasswordEncoder pinEncoder;
    @Mock private com.application.e_wallet.notification.Notifier notifier;

        @Mock private com.application.e_wallet.common.identifier.IdentifierService identifierService;

@InjectMocks
    private StaffService staffService;

    @org.junit.jupiter.api.BeforeEach
    void stubIdentifiers() {
        org.mockito.Mockito.lenient().when(identifierService.nextStaffNumber()).thenReturn("ST-0005");
    }

    @Test
    void createStaffBuildsUserAndStaffProfileWithCreatedBy() {
        StaffCreationRequest request = new StaffCreationRequest(
                "Jane", "M", "Doe", "jane.doe@example.com", "0712345678", "Accountant"
        );

        RoleEntity adminRole = RoleEntity.builder().id(UUID.randomUUID()).name("ADMIN").build();

        UUID adminUserId = UUID.randomUUID();
        UserEntity admin = UserEntity.builder().id(adminUserId).build();

        lenient().when(userRepository.existsByEmail(anyString())).thenReturn(false);
        lenient().when(userRepository.existsByPhone(anyString())).thenReturn(false);
        when(phoneNumberNormalizer.normalize("0712345678")).thenReturn("+255712345678");
        when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(adminRole));
        when(otpGenerator.generate(4)).thenReturn("1234");
        when(pinEncoder.encode(anyString())).thenReturn("hashed-pin");
        when(userRepository.findById(adminUserId)).thenReturn(Optional.of(admin));

        UUID userId = UUID.randomUUID();
        when(userRepository.save(any())).thenAnswer(inv -> {
            UserEntity user = inv.getArgument(0);
            user.setId(userId);
            return user;
        });

        UUID staffId = UUID.randomUUID();
        when(staffRepository.save(any())).thenAnswer(inv -> {
            StaffEntity staff = inv.getArgument(0);
            staff.setId(staffId);
            return staff;
        });

        StaffOnboardResponse response = staffService.createStaff(adminUserId, request);

        assertThat(response.firstName()).isEqualTo("Jane");
        assertThat(response.jobTitle()).isEqualTo("Accountant");
        assertThat(response.status()).isEqualTo(StaffStatus.ACTIVE.name());

        ArgumentCaptor<StaffEntity> staffCaptor = ArgumentCaptor.forClass(StaffEntity.class);
        verify(staffRepository).save(staffCaptor.capture());
        assertThat(staffCaptor.getValue().getCreatedBy()).isEqualTo(admin);

        ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getRole()).isEqualTo(adminRole);
        assertThat(userCaptor.getValue().getLoginUsername()).isEqualTo("ST-0005");
    }

    @Test
    void getStaffThrowsWhenNotFound() {
        UUID staffId = UUID.randomUUID();
        when(staffRepository.findById(staffId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> staffService.getStaff(staffId))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void updateStaffUpdatesEditableFieldsOnly() {
        UUID staffId = UUID.randomUUID();
        StaffEntity staff = StaffEntity.builder()
                .id(staffId)
                .firstName("Old")
                .middleName("O")
                .lastName("Name")
                .jobTitle("Clerk")
                .status(StaffStatus.ACTIVE)
                .build();

        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(staffRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StaffUpdateRequest request = new StaffUpdateRequest("New", "N", "Name", "Senior Accountant");

        StaffUpdateResponse response = staffService.updateStaff(staffId, request);

        assertThat(response.firstName()).isEqualTo("New");
        assertThat(response.jobTitle()).isEqualTo("Senior Accountant");
    }

    @Test
    void deactivateStaffSetsBothUserAndStaffStatusAndRevokesSessions() {
        UUID staffId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        UserEntity user = UserEntity.builder().id(userId).status(UserStatus.ACTIVE).build();
        StaffEntity staff = StaffEntity.builder().id(staffId).user(user).status(StaffStatus.ACTIVE).build();

        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(staffRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        staffService.deactivateStaff(staffId);

        assertThat(user.getStatus()).isEqualTo(UserStatus.DISABLED);
        assertThat(staff.getStatus()).isEqualTo(StaffStatus.DISABLED);
        verify(userSessionRepository).deleteAllByUserId(userId);
    }
}
