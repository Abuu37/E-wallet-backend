package com.application.e_wallet.auth.dto;

import com.application.e_wallet.user.entity.UserStatus;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class AccountStatusResponse {

    private UserStatus status;
    private boolean active;
    private String message;
}
