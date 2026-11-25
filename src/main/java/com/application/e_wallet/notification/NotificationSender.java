package com.application.e_wallet.notification;

import java.util.Map;

/**
 * The one door every message goes through. Today the implementation sends email (EmailNotificationSender);
 * an SmsNotificationSender replaces it when the SMS provider is integrated, with no change to any caller.
 * Never put a PIN in params except the one-time PIN itself, and never a full account number.
 */
public interface NotificationSender {

    void send(NotificationKey key, NotificationRecipient to, Map<String, String> params);
}
