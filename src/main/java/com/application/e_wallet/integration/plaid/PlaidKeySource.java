package com.application.e_wallet.integration.plaid;

import java.security.interfaces.ECPublicKey;
import java.util.Optional;

/** Where Plaid's webhook verification public keys come from. Replaceable in tests. */
public interface PlaidKeySource {

    Optional<ECPublicKey> publicKey(String keyId);
}
