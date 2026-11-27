package com.application.e_wallet.staff.service;

import com.application.e_wallet.auth.repository.UserSessionRepository;
import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.common.exception.DuplicationResourceException;
import com.application.e_wallet.common.validation.PhoneNumberNormalizer;
import com.application.e_wallet.otp.service.EmailService;
import com.application.e_wallet.otp.service.OtpGenerator;
import com.application.e_wallet.role.entity.RoleEntity;
import com.application.e_wallet.role.repository.RoleRepository;
import com.application.e_wallet.staff.dto.StaffCreationRequest;
import com.application.e_wallet.staff.dto.StaffDetailResponse;
import com.application.e_wallet.staff.dto.StaffOnboardResponse;
import com.application.e_wallet.staff.dto.StaffSummaryResponse;
import com.application.e_wallet.staff.dto.StaffUpdateRequest;
import com.application.e_wallet.staff.dto.StaffUpdateResponse;
import com.application.e_wallet.staff.entity.StaffEntity;
import com.application.e_wallet.staff.entity.StaffStatus;
import com.application.e_wallet.staff.repository.StaffRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.entity.UserStatus;
import com.application.e_wallet.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StaffService {
    private final com.application.e_wallet.common.identifier.IdentifierService identifierService;

    private static final int ONE_TIME_PIN_LENGTH = 4;
    private static final String STAFF_ROLE = "ADMIN";

    private final StaffRepository staffRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserSessionRepository userSessionRepository;
    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final OtpGenerator otpGenerator;
    private final PasswordEncoder pinEncoder;
    private final com.application.e_wallet.notification.Notifier notifier;
    private final com.application.e_wallet.audit.service.AuditService auditService;

    //======================== CREATE A STAFF MEMBER ==================== //
    @Transactional
    public StaffOnboardResponse createStaff(UUID adminUserId, StaffCreationRequest request) {

        String email = request.email().trim().toLowerCase();
        String normalizedPhone = phoneNumberNormalizer.normalize(request.phone());

        if (userRepository.existsByEmail(email)) {
            throw new DuplicationResourceException("Email already exists");
        }

        if (userRepository.existsByPhone(normalizedPhone)) {
            throw new DuplicationResourceException("Phone already exists");
        }

        RoleEntity staffRole = roleRepository.findByName(STAFF_ROLE)
                .orElseThrow(() -> new IllegalStateException("ADMIN role not found"));

        UserEntity admin = userRepository.findById(adminUserId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));

        String middleName = request.middleName() != null ? request.middleName().trim() : "";
        String oneTimePin = otpGenerator.generate(ONE_TIME_PIN_LENGTH);

        String staffNo = identifierService.nextStaffNumber();

        UserEntity user = UserEntity.builder()
                .loginUsername(staffNo)
                .email(email)
                .phone(normalizedPhone)
                .pinHash(pinEncoder.encode(oneTimePin))
                .status(UserStatus.PIN_CHANGE_REQUIRED)
                .role(staffRole)
                .build();

        UserEntity savedUser = userRepository.save(user); //======== user saved in database ========

        StaffEntity staff = StaffEntity.builder()
                .user(savedUser)
                .staffNo(staffNo)
                .jobTitle(request.jobTitle().trim())
                .firstName(request.firstName().trim())
                .middleName(middleName)
                .lastName(request.lastName().trim())
                .email(email)
                .phoneNumber(normalizedPhone)
                .createdBy(admin)
                .status(StaffStatus.ACTIVE)
                .build();

        StaffEntity savedStaff = staffRepository.save(staff); //=== Staff profile created ====

        // Sends the one-time PIN the staff member must use to log in and then change.
        notifier.notify(com.application.e_wallet.notification.NotificationKey.S5, com.application.e_wallet.notification.NotificationRecipient.of(savedUser),
                java.util.Map.of("pin", oneTimePin));

        return toOnboardResponse(savedStaff, savedUser);
    }

    //======================= LIST STAFF ====================//
    @Transactional(readOnly = true)
    public List<StaffSummaryResponse> listStaff() {
        return staffRepository.findAll().stream()
                .map(this::toSummaryResponse)
                .toList();
    }

    // =================== GET A SINGLE STAFF MEMBER ================== //
    @Transactional(readOnly = true)
    public StaffDetailResponse getStaff(UUID staffId) {
        StaffEntity staff = findStaffOrThrow(staffId);
        return toDetailResponse(staff);
    }

    //====================== UPDATE STAFF ======================== //
    @Transactional
    public StaffUpdateResponse updateStaff(UUID staffId, StaffUpdateRequest request) {
        StaffEntity staff = findStaffOrThrow(staffId);

        staff.setFirstName(request.firstName().trim());
        staff.setMiddleName(request.middleName() != null ? request.middleName().trim() : "");
        staff.setLastName(request.lastName().trim());
        staff.setJobTitle(request.jobTitle().trim());

        StaffEntity savedStaff = staffRepository.save(staff);

        return toUpdateResponse(savedStaff);
    }

    // ==================== DEACTIVATE STAFF ===================== //
    @Transactional
    public void deactivateStaff(UUID staffId) {

        StaffEntity staff = findStaffOrThrow(staffId);
        UserEntity user = staff.getUser();

        if (user.getStatus() == UserStatus.DISABLED && staff.getStatus() == StaffStatus.DISABLED) {
            return;
        }

        user.setStatus(UserStatus.DISABLED);
        userRepository.save(user);

        staff.setStatus(StaffStatus.DISABLED);
        staffRepository.save(staff);

        userSessionRepository.deleteAllByUserId(user.getId());
    }

    private StaffEntity findStaffOrThrow(UUID staffId) {
        return staffRepository.findById(staffId)
                .orElseThrow(() -> new BadRequestException("Staff member not found"));
    }

    private StaffOnboardResponse toOnboardResponse(StaffEntity staff, UserEntity user) {
        return new StaffOnboardResponse(
                staff.getId(),
                staff.getStaffNo(),
                user.getId(),
                staff.getFirstName(),
                staff.getMiddleName(),
                staff.getLastName(),
                staff.getEmail(),
                staff.getPhoneNumber(),
                staff.getJobTitle(),
                staff.getStatus().name()
        );
    }

    private StaffDetailResponse toDetailResponse(StaffEntity staff) {
        return new StaffDetailResponse(
                staff.getId(),
                staff.getStaffNo(),
                staff.getUser().getId(),
                staff.getFirstName(),
                staff.getMiddleName(),
                staff.getLastName(),
                staff.getEmail(),
                staff.getPhoneNumber(),
                staff.getJobTitle(),
                staff.getStatus().name(),
                staff.getCreatedAt(),
                staff.getUpdatedAt()
        );
    }

    private StaffSummaryResponse toSummaryResponse(StaffEntity staff) {
        return new StaffSummaryResponse(
                staff.getId(),
                staff.getStaffNo(),
                staff.getFirstName(),
                staff.getMiddleName(),
                staff.getLastName(),
                staff.getPhoneNumber(),
                staff.getJobTitle(),
                staff.getStatus().name(),
                staff.getUser().getRole().getName()
        );
    }

    private StaffUpdateResponse toUpdateResponse(StaffEntity staff) {
        return new StaffUpdateResponse(
                staff.getId(),
                staff.getFirstName(),
                staff.getMiddleName(),
                staff.getLastName(),
                staff.getJobTitle(),
                staff.getStatus().name()
        );
    }


    //======== Administrator resets a staff member's PIN: a new one-time PIN by email, every session ended ========
    @Transactional
    public void resetPin(UUID staffId) {
        StaffEntity staff = staffRepository.findById(staffId)
                .orElseThrow(() -> new BadRequestException("Staff member not found"));
        UserEntity user = staff.getUser();

        String oneTimePin = otpGenerator.generate(ONE_TIME_PIN_LENGTH);
        user.setPinHash(pinEncoder.encode(oneTimePin));
        user.setStatus(UserStatus.PIN_CHANGE_REQUIRED);
        user.setFailedPinAttempts(0);
        user.setUnlockRequiresOtp(false);
        user.setLockedUntil(null);
        userRepository.save(user);
        userSessionRepository.deleteAllByUserId(user.getId());

        auditService.recordAsCurrentUser("STAFF_PIN_RESET", "STAFF", staff.getId(), null, null, "PIN reset by an administrator");
        notifier.notify(com.application.e_wallet.notification.NotificationKey.S5, com.application.e_wallet.notification.NotificationRecipient.of(user), java.util.Map.of("pin", oneTimePin));
    }

    /** The signed-in staff member's own name and job title. */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public com.application.e_wallet.staff.dto.StaffProfileResponse profile(java.util.UUID userId) {
        StaffEntity staff = staffRepository.findByUserId(userId).orElseThrow(() -> new BadRequestException("Staff member not found"));
        String full = java.util.stream.Stream.of(staff.getFirstName(), staff.getMiddleName(), staff.getLastName())
                .filter(part -> part != null && !part.isBlank()).collect(java.util.stream.Collectors.joining(" "));
        return new com.application.e_wallet.staff.dto.StaffProfileResponse(full, staff.getFirstName(), staff.getJobTitle());
    }
}
