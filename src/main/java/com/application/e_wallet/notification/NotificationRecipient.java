package com.application.e_wallet.notification;

import com.application.e_wallet.user.entity.UserEntity;

/** Where a message can go. Email is used today; the phone is there for when SMS replaces it. */
public record NotificationRecipient(String email, String phone) {

    public static NotificationRecipient of(UserEntity user) {
        return new NotificationRecipient(user.getEmail(), user.getPhone());
    }
}
