package com.application.e_wallet.notification;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTemplatesTest {

    @Test
    void everyKeyHasWording() {
        for (NotificationKey key : NotificationKey.values()) {
            assertThat(NotificationTemplates.message(key, Map.of())).as(key.name()).isNotBlank();
            assertThat(NotificationTemplates.subject(key)).as(key.name()).isNotBlank();
        }
    }

    @Test
    void placeholdersAreFilledFromTheParams() {
        String text = NotificationTemplates.message(NotificationKey.S1, Map.of("code", "482913", "minutes", "5"));

        assertThat(text).isEqualTo("WalletPesa code: 482913. Expires in 5 min. Don't share it.");
    }

    @Test
    void onlyTheOneTimePinMessagesCanCarryAPin() {
        for (NotificationKey key : NotificationKey.values()) {
            boolean carriesPin = NotificationTemplates.message(key, Map.of()).contains("{pin}");
            boolean oneTimePin = key == NotificationKey.S4 || key == NotificationKey.S5 || key == NotificationKey.T1;
            assertThat(carriesPin).as("%s must not take a PIN", key).isEqualTo(oneTimePin);
        }
    }

    @Test
    void aPinChangedMessageDoesNotRepeatAnyPin() {
        String text = NotificationTemplates.message(NotificationKey.S3, Map.of("pin", "1234"));

        assertThat(text).doesNotContain("1234");
    }
}
