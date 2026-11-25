package com.application.e_wallet.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

/**
 * What the business code calls. A message is sent after the database transaction commits, never inside it,
 * so nobody is told about money that was rolled back. Without a transaction it is sent straight away.
 * A failed send is logged and never breaks the action that caused it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Notifier {

    private final NotificationSender sender;

    public void notify(NotificationKey key, NotificationRecipient to, Map<String, String> params) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deliver(key, to, params);
                }
            });
        } else {
            deliver(key, to, params);
        }
    }

    private void deliver(NotificationKey key, NotificationRecipient to, Map<String, String> params) {
        try {
            sender.send(key, to, params);
        } catch (Exception exception) {
            log.error("Could not send notification {}", key, exception);
        }
    }
}
