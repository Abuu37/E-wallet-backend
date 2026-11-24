package com.application.e_wallet.role.dto;

import java.util.List;

public record ModuleGroup(String name, String description, List<PermissionItem> permissions) {
}
