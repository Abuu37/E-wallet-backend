package com.application.e_wallet.auth.service;

import com.application.e_wallet.auth.dto.CustomerRegistrationRequest;
import com.application.e_wallet.auth.dto.RegistrationResponse;
import com.application.e_wallet.role.entity.RoleEntity;
import com.application.e_wallet.role.repository.RoleRepository;
import com.application.e_wallet.user.entity.UserEntity;
import com.application.e_wallet.user.entity.UserStatus;
import com.application.e_wallet.user.repository.UserRepository;
import com.application.e_wallet.common.exception.DuplicationResourceException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public RegistrationResponse registerCustomer(CustomerRegistrationRequest request){

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicationResourceException("Email already exists");
        }

        if (userRepository.existsByPhone(request.getPhone())) {
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
                .phone(request.getPhone().trim())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .status(UserStatus.PENDING)
                .build();

        user.getRoles().add(customerRole);

        UserEntity savedUser = userRepository.save(user);

        return RegistrationResponse.builder()
                .userId(savedUser.getId())
                .message("Customer registered successfully")
                .status(savedUser.getStatus())
                .build();
    }
}
