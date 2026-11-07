package com.application.e_wallet.auth.service;

import com.application.e_wallet.common.exception.AuthenticationException;
import com.application.e_wallet.user.entity.UserEntity;
import org.springframework.stereotype.Service;

@Service
public class AccountStatusService {

    public void validateLoginStatus(UserEntity user) {

        if (user.getStatus() == null) {
            throw new AuthenticationException(
                    "Account Status is not configured"
            );
        }

        switch (user.getStatus()) {

            case ACTIVE -> {
                // CAN CONTINUE
            }
            case PIN_CHANGE_REQUIRED -> {
                // CAN CONTINUE — must log in with the one-time PIN to change it via changePin()
            }
            case PENDING -> {
                throw new AuthenticationException(
                        "Account is pending "
                );
            }
            case AWAITING_PIN -> {
                throw new AuthenticationException(
                        "Account setup is incomplete — please set your PIN"
                );
            }
            case SUSPENDED -> {
                throw new AuthenticationException(
                        "Account is suspended "
                );
            }
            case BLOCKED -> {
                throw new AuthenticationException(
                        "Account is blocked "
                );
            }
            case CLOSED -> {
                throw new AuthenticationException(
                        "Account is closed "
                );
            }
            default ->
                throw new AuthenticationException(
                        "Account status is not active"
                );
        }
    }
}
