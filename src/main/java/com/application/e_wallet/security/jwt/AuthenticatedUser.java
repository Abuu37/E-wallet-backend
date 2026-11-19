package com.application.e_wallet.security.jwt;

import java.util.UUID;

public record AuthenticatedUser(UUID userId, String email, String status) {
}
