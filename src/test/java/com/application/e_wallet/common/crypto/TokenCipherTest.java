package com.application.e_wallet.common.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void roundTripsAndNeverStoresTheValueInClear() {
        TokenCipher cipher = new TokenCipher(KEY);
        String stored = cipher.encrypt("access-sandbox-1234");
        assertThat(stored).doesNotContain("access-sandbox");
        assertThat(cipher.decrypt(stored)).isEqualTo("access-sandbox-1234");
    }

    @Test
    void theSameValueEncryptsDifferentlyEachTime() {
        TokenCipher cipher = new TokenCipher(KEY);
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    void aTamperedValueIsRejected() {
        TokenCipher cipher = new TokenCipher(KEY);
        byte[] bytes = Base64.getDecoder().decode(cipher.encrypt("secret"));
        bytes[bytes.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(bytes))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withoutAKeyItRefusesInsteadOfStoringInClear() {
        assertThatThrownBy(() -> new TokenCipher("").encrypt("x")).isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_ENCRYPTION_KEY");
        assertThatThrownBy(() -> new TokenCipher("c2hvcnQ=").encrypt("x")).isInstanceOf(IllegalStateException.class);
    }
}
