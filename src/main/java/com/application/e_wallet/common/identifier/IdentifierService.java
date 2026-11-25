package com.application.e_wallet.common.identifier;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Hands out the readable ids people see: the customer reference (CU-000123), the agent ID (AG-10234) and
 * the staff number (ST-0005). Each comes from a database sequence, so two requests never get the same one.
 * The same value fills users.username, so nobody ever types a username.
 */
@Service
@RequiredArgsConstructor
public class IdentifierService {

    private final JdbcTemplate jdbcTemplate;

    public String nextCustomerRef() {
        return "CU-" + String.format("%06d", next("customer_ref_seq"));
    }

    public String nextAgentNumber() {
        return "AG-" + next("agent_no_seq");
    }

    public String nextStaffNumber() {
        return "ST-" + String.format("%04d", next("staff_no_seq"));
    }

    private long next(String sequence) {
        Long value = jdbcTemplate.queryForObject("SELECT nextval('" + sequence + "')", Long.class);
        if (value == null) {
            throw new IllegalStateException("Sequence " + sequence + " returned nothing");
        }
        return value;
    }
}
