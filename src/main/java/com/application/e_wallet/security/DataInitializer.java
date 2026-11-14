package com.application.e_wallet.security;

import com.application.e_wallet.role.entity.RoleEntity;
import com.application.e_wallet.role.repository.RoleRepository;
import com.application.e_wallet.staff.entity.StaffEntity;
import com.application.e_wallet.staff.entity.StaffStatus;
import com.application.e_wallet.staff.repository.StaffRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.entity.UserStatus;
import com.application.e_wallet.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.initial-admin", name = "enabled", havingValue = "true")
public class DataInitializer implements CommandLineRunner {

    private static final String ADMIN_ROLE = "ADMIN";

    // Hardcoded bootstrap-only job title - this seeds exactly one row, so a dedicated
    // .env property isn't worth it. Flag to the project owner if this should become
    // configurable instead.
    private static final String BOOTSTRAP_JOB_TITLE = "System Administrator";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final StaffRepository staffRepository;
    private final PasswordEncoder pinEncoder;

    @Value("${app.initial-admin.username}")
    private String username;

    @Value("${app.initial-admin.first-name}")
    private String firstName;

    @Value("${app.initial-admin.middle-name}")
    private String middleName;

    @Value("${app.initial-admin.last-name}")
    private String lastName;

    @Value("${app.initial-admin.email}")
    private String email;

    @Value("${app.initial-admin.phone}")
    private String phone;

    @Value("${app.initial-admin.pin}")
    private String pin;

    @Override
    @Transactional
    public void run(String... args) {

        if (userRepository.findByEmail(email).isPresent()) {
            return;
        }

        if (!pin.matches("^\\d{4}$")) {
            throw new IllegalStateException(
                    "app.initial-admin.pin must be exactly 4 digits"
            );
        }

        RoleEntity adminRole = roleRepository.findByName(ADMIN_ROLE)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "ADMIN role not found"
                        ));

        UserEntity admin = UserEntity.builder()
                .loginUsername(username)
                .email(email)
                .phone(phone)
                .pinHash(pinEncoder.encode(pin))
                .status(UserStatus.ACTIVE)
                .role(adminRole)
                .build();

        UserEntity savedAdmin = userRepository.save(admin);

        // Bootstrap record - no acting admin created it, so createdBy stays null.
        StaffEntity staff = StaffEntity.builder()
                .user(savedAdmin)
                .staffNo(generateStaffNumber())
                .jobTitle(BOOTSTRAP_JOB_TITLE)
                .firstName(firstName)
                .middleName(middleName)
                .lastName(lastName)
                .email(email)
                .phoneNumber(phone)
                .createdBy(null)
                .status(StaffStatus.ACTIVE)
                .build();

        staffRepository.save(staff);

        log.info("Initial admin account created (email={})", email);
    }

    private String generateStaffNumber() {
        String staffNo;
        do {
            staffNo = "STF-" + UUID.randomUUID().toString().replace("-", "")
                    .substring(0, 12).toUpperCase();
        } while (staffRepository.existsByStaffNo(staffNo));

        return staffNo;
    }
}
