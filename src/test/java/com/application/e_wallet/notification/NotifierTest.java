package com.application.e_wallet.notification;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class NotifierTest {

    private final List<String> sent = new ArrayList<>();
    private final NotificationSender recording = (key, to, params) -> sent.add(key + "->" + to.email());
    private final NotificationRecipient amina = new NotificationRecipient("amina@example.com", "+255712345678");

    @AfterEach
    void cleanUp() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void withoutATransactionTheMessageGoesStraightAway() {
        new Notifier(recording).notify(NotificationKey.S3, amina, Map.of());

        assertThat(sent).containsExactly("S3->amina@example.com");
    }

    @Test
    void insideATransactionTheMessageWaitsForTheCommit() {
        TransactionSynchronizationManager.initSynchronization();

        new Notifier(recording).notify(NotificationKey.C3, amina, Map.of());
        assertThat(sent).isEmpty();

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(sent).containsExactly("C3->amina@example.com");
    }

    @Test
    void aRolledBackTransactionNeverSendsAnything() {
        TransactionSynchronizationManager.initSynchronization();

        new Notifier(recording).notify(NotificationKey.C3, amina, Map.of());
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(sent).isEmpty();
    }

    @Test
    void aFailedSendNeverBreaksTheCaller() {
        NotificationSender failing = (key, to, params) -> { throw new IllegalStateException("mail server down"); };

        assertThatCode(() -> new Notifier(failing).notify(NotificationKey.S1, amina, Map.of())).doesNotThrowAnyException();
    }
}
