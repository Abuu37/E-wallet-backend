package com.application.e_wallet.agent.dto;

import com.application.e_wallet.agent.entity.Gender;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record AgentResponse(
        UUID agentId,
        String agentNumber,
        UUID userId,
        String firstName,
        String middleName,
        String lastName,
        String email,
        String phone,
        Gender gender,
        LocalDate dateOfBirth,
        String regionId,
        String regionName,
        String districtId,
        String districtName,
        String walletNumber,
        String accountNumber,
        BigDecimal currentBalance,
        FloatHealth floatHealth,
        // No commission model yet; placeholder until that feature exists.
        BigDecimal commission,
        // No transaction history wired to agents yet; placeholder until that feature exists.
        long transactionCount,
        String status
) {
}
