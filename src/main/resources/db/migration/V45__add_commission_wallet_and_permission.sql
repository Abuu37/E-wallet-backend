-- V45: Company-wide COMMISSION wallet (singleton, funded from COMPANY_MASTER_ACCOUNT)
-- and the COMMISSION_MANAGE permission that guards topping it up.

-- 1. Extend the singleton-wallet-type constraint to include COMMISSION.
DROP INDEX uk_wallets_singleton_company_types;
CREATE UNIQUE INDEX uk_wallets_singleton_company_types
    ON wallets (wallet_type)
    WHERE wallet_type IN ('COMPANY_MASTER_ACCOUNT', 'REVENUE', 'ESCROW_BANK', 'COMMISSION');

-- 2. Seed the COMMISSION wallet, owned by the same system user as the other
--    company-level wallets (seeded in V26).
INSERT INTO wallets (id, user_id, wallet_type, account_number, currency, balance, status, version, account_name, created_at, updated_at)
VALUES (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'COMMISSION',
        'COMPANY-COMMISSION-ACCOUNT', 'TZS', 0, 'ACTIVE', 0, 'Company Commission Pool', NOW(), NOW());

-- 3. Rename stale transaction_type values before any new row can be written as
--    SERVICE_COMMISSION — the Java enum constant COMMISSION is being renamed and
--    this column has no DB check constraint, so old rows must move with it or
--    Hibernate will fail to deserialize them (same class of bug as UserStatus.PENDING).
UPDATE transactions SET transaction_type = 'SERVICE_COMMISSION' WHERE transaction_type = 'COMMISSION';

-- 4. New permission guarding commission pool funding.
INSERT INTO permissions (id, name, description, module_reference, created_at, updated_at)
VALUES (gen_random_uuid(), 'COMMISSION_MANAGE', 'Fund the company commission pool from the master wallet',
        (SELECT id FROM modules WHERE name = 'FINANCE'), NOW(), NOW());

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name = 'ADMIN' AND p.name = 'COMMISSION_MANAGE';
