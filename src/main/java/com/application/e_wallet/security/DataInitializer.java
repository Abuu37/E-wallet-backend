package com.application.e_wallet.security;

import com.application.e_wallet.role.entity.RoleEntity;
import com.application.e_wallet.role.repository.RoleRepository;
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

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.initial-admin", name = "enabled", havingValue = "true")
public class DataInitializer implements CommandLineRunner {

    private static final String ADMIN_ROLE = "ADMIN";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

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

    @Value("${app.initial-admin.password}")
    private String password;

    @Override
    @Transactional
    public void run(String... args) {

        if (userRepository.findByEmail(email).isPresent()) {
            return;
        }

        RoleEntity adminRole = roleRepository.findByName(ADMIN_ROLE)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "ADMIN role not found"
                        ));

        UserEntity admin = UserEntity.builder()
                .firstName(firstName)
                .middleName(middleName)
                .lastName(lastName)
                .email(email)
                .phone(phone)
                .passwordHash(passwordEncoder.encode(password))
                .status(UserStatus.ACTIVE)
                .build();

        admin.getRoles().add(adminRole);

        userRepository.save(admin);

        log.info("Initial admin account created (email={})", email);
    }
}
