package com.application.e_wallet.auth.dto;

import com.application.e_wallet.user.entity.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegistrationResponse {

    private UUID userId;
    private String message;
    private UserStatus status;
}
