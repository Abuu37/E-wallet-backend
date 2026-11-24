package com.application.e_wallet.audit.dto;

import java.util.List;

public record AuditLogListResponse(List<AuditLogItem> entries, int page, int size, long totalElements) {
}
