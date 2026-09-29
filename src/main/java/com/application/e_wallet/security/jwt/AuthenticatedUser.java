package com.application.e_wallet.security.jwt;

import java.util.UUID;

// The principal set on SecurityContextHolder after a valid access token is
// parsed, so downstream code can read the user's id and email without a DB lookup.
public record AuthenticatedUser(UUID userId, String email) {
}
