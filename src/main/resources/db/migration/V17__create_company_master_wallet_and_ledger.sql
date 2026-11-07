-- V17: Company master wallet + ledger (journal) tables, and permission grants.

CREATE TABLE company_master_wallet (
    id UUID PRIMARY KEY,
    singleton_key SMALLINT NOT NULL DEFAULT 1,
    wallet_name VARCHAR(100) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    unallocated_float NUMERIC(19,2) NOT NULL DEFAULT 0,
    total_distributed_float NUMERIC(19,2) NOT NULL DEFAULT 0,
    escrow_bank_account_no VARCHAR(50),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT uk_company_master_wallet_singleton UNIQUE (singleton_key)
);

INSERT INTO company_master_wallet (
    id, singleton_key, wallet_name, currency,
    unallocated_float, total_distributed_float, escrow_bank_account_no,
    created_at, updated_at
)
VALUES (
    gen_random_uuid(), 1, 'Company Trust Account', 'TZS',
    0, 0, NULL,
    NOW(), NOW()
);

CREATE TABLE transaction_journal (
    id UUID PRIMARY KEY,
    reference_number VARCHAR(60) NOT NULL,
    transaction_type VARCHAR(30) NOT NULL,
    description VARCHAR(255) NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT uk_transaction_journal_reference_number UNIQUE (reference_number),
    CONSTRAINT fk_transaction_journal_created_by FOREIGN KEY (created_by) REFERENCES users (id)
);

CREATE TABLE transaction_journal_line (
    id UUID PRIMARY KEY,
    journal_id UUID NOT NULL,
    ledger_account_type VARCHAR(30) NOT NULL,
    account_ref_id UUID NOT NULL,
    entry_type VARCHAR(10) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,

    CONSTRAINT fk_transaction_journal_line_journal FOREIGN KEY (journal_id) REFERENCES transaction_journal (id)
);

-- Grant the previously-orphaned view permissions (seeded in V6, never assigned) to ADMIN.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN'
AND p.name IN ('COMPANY_WALLET_VIEW', 'LEDGER_VIEW');
