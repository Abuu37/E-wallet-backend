package com.application.e_wallet.common.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts secrets that must be stored but never shown, such as a bank access token. AES-256-GCM with a fresh random
 * nonce per value; the nonce is stored in front of the ciphertext. The key comes from APP_ENCRYPTION_KEY only.
 */
@Component
public class TokenCipher {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] key;

    public TokenCipher(@Value("${app.encryption-key:}") String base64Key) {
        byte[] decoded = base64Key == null || base64Key.isBlank() ? new byte[0] : Base64.getDecoder().decode(base64Key.trim());
        this.key = decoded.length == 32 ? decoded : null;
    }

    public String encrypt(String plain) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(requireKey(), "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt the value", e);
        }
    }

    public String decrypt(String stored) {
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(requireKey(), "AES"), new GCMParameterSpec(TAG_BITS, all, 0, NONCE_BYTES));
            return new String(cipher.doFinal(all, NONCE_BYTES, all.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not decrypt the value", e);
        }
    }

    private byte[] requireKey() {
        if (key == null) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY must be 32 random bytes, base64 encoded");
        }
        return key;
    }
}
