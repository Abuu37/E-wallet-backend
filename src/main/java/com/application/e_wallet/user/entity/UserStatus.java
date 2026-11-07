package com.application.e_wallet.user.entity;

public enum UserStatus {
    PENDING,
    AWAITING_PIN,
    PIN_CHANGE_REQUIRED,
    ACTIVE,
    SUSPENDED,
    DISABLED,
    BLOCKED,
    CLOSED
}
