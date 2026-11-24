package com.application.e_wallet.common.exception;

/** An outside provider (identity, bank linking) failed or could not be reached. The message is safe to show. */
public class ProviderUnavailableException extends RuntimeException {

    public ProviderUnavailableException(String message) {
        super(message);
    }
}
