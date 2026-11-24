package com.application.e_wallet.role.dto;

import java.util.List;

public record RoleListResponse(List<RoleItem> roles, List<ModuleGroup> modules) {
}
