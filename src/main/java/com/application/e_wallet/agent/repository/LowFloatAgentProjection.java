package com.application.e_wallet.agent.repository;

import java.math.BigDecimal;
import java.util.UUID;

public interface LowFloatAgentProjection {
    UUID getAgentId();

    String getAgentCode();

    String getBusinessName();

    String getPhoneNumber();

    String getFloatAccountNumber();

    BigDecimal getCurrentBalance();
}
