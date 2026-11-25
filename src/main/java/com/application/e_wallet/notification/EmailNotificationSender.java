package com.application.e_wallet.notification;

import com.application.e_wallet.otp.service.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Sends every message by email, with the same wording an SMS would carry. */
@Component
@RequiredArgsConstructor
public class EmailNotificationSender implements NotificationSender {

    private final EmailService emailService;

    @Override
    public void send(NotificationKey key, NotificationRecipient to, Map<String, String> params) {
        if (to == null || to.email() == null || to.email().isBlank()) {
            return;
        }
        String message = NotificationTemplates.message(key, params);
        emailService.sendPlain(to.email(), NotificationTemplates.subject(key),
                "Hello,\n\n" + message + "\n\nWalletPesa");
    }
}
