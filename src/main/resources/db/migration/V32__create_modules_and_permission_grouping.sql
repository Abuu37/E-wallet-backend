CREATE TABLE modules (
    id UUID PRIMARY KEY,
    reference UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    name VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

INSERT INTO modules (id, name, description) VALUES
    (gen_random_uuid(), 'USER', 'User account management'),
    (gen_random_uuid(), 'CUSTOMER', 'Customer profile management'),
    (gen_random_uuid(), 'WALLET', 'Wallet viewing and transfers'),
    (gen_random_uuid(), 'TRANSACTION', 'Transaction history, cash-in and cash-out'),
    (gen_random_uuid(), 'AGENT', 'Agent and float operations'),
    (gen_random_uuid(), 'LOAN', 'Loan application and repayment'),
    (gen_random_uuid(), 'FINANCE', 'Reconciliation, ledger and financial reporting'),
    (gen_random_uuid(), 'ROLE', 'Role and permission administration'),
    (gen_random_uuid(), 'TARIFF', 'Fee and charge configuration'),
    (gen_random_uuid(), 'SYSTEM', 'System-level configuration');

ALTER TABLE permissions ADD COLUMN module_reference UUID;

UPDATE permissions SET module_reference = (SELECT id FROM modules WHERE name = 'USER')
    WHERE name IN ('USER_CREATE', 'USER_VIEW', 'USER_UPDATE');
UPDATE permissions SET module_reference = (SELECT id FROM modules WHERE name = 'CUSTOMER')
    WHERE name IN ('CHECK_BALANCE', 'CUSTOMER_VIEW', 'CUSTOMER_UPDATE');
UPDATE permissions SET module_reference = (SELECT id FROM modules WHERE name = 'WALLET')
    WHERE name IN ('WALLET_VIEW', 'COMPANY_WALLET_VIEW', 'WALLET_TO_WALLET_TRANSFER', 'WALLET_TO_BANK_TRANSFER');
-- Corrected during execution: V6 seeded 'TRANSACTION_VIEW' and 'CASHIER_VIEW', but V11 (split
-- TRANSACTION_VIEW into three granular permissions, deleting the coarse one) and V16 (renamed
-- CASHIER_VIEW to AGENT_VIEW) both run after V6 and before this migration — the names below
-- are what actually exists by the time V32 runs, confirmed by querying the rebuilt database.
UPDATE permissions SET module_reference = (SELECT id FROM modules WHERE name = 'TRANSACTION')
    WHERE name IN ('TRANSACTION_VIEW_ALL', 'TRANSACTION_VIEW_OWN', 'TRANSACTION_VIEW_DETAILS', 'TOPUP_CREATE', 'TOPUP_VIEW', 'WITHDRAWAL_CREATE');
UPDATE permissions SET module_reference = (SELECT id FROM modules WHERE name = 'AGENT')
    WHERE name IN ('AGENT_VIEW', 'FLOAT_VIEW', 'REQUEST_FLOAT', 'FLOAT_MANAGE');
UPDATE permissions SET module_reference = (SELECT id FROM modules WHERE name = 'LOAN')
    WHERE name IN ('LOAN_VIEW', 'LOAN_APPLY', 'LOAN_APPROVE', 'LOAN_REPAY');
UPDATE permissions SET module_reference = (SELECT id FROM modules WHERE name = 'FINANCE')
    WHERE name IN ('RECONCILIATION_VIEW', 'RECONCILIATION_MANAGE', 'FINANCIAL_REPORT_VIEW', 'LEDGER_VIEW');
UPDATE permissions SET module_reference = (SELECT id FROM modules WHERE name = 'ROLE')
    WHERE name IN ('ROLE_VIEW', 'ROLE_ASSIGN');
UPDATE permissions SET module_reference = (SELECT id FROM modules WHERE name = 'TARIFF')
    WHERE name IN ('CHARGE_VIEW', 'CHARGE_MANAGE', 'FEES_MANAGE');
UPDATE permissions SET module_reference = (SELECT id FROM modules WHERE name = 'SYSTEM')
    WHERE name IN ('SYSTEM_CONFIG');

ALTER TABLE permissions ALTER COLUMN module_reference SET NOT NULL;
ALTER TABLE permissions ADD CONSTRAINT fk_permissions_module FOREIGN KEY (module_reference) REFERENCES modules(id);
