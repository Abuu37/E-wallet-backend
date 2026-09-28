package com.application.e_wallet.common.event;

import java.util.UUID;

public record UserRegisteredEvent(UUID userId, String email) {
}
