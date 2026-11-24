package com.application.e_wallet.role.dto;

import java.util.List;
import java.util.UUID;

public record RoleItem(UUID id, String name, String description, int permissionCount, long userCount, List<String> permissions) {
}
