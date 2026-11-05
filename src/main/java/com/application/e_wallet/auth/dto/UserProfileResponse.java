package com.application.e_wallet.auth.dto;

import com.application.e_wallet.user.entity.UserStatus;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Builder
public class UserProfileResponse {

    private UUID userId;
    private String firstName;
    private String middleName;
    private String lastName;
    private String email;
    private String phone;
    private UserStatus status;
}
