-- 1. The system user that owns every company-level wallet. BLOCKED + null pin_hash
--    so it can never log in (pin_hash nullability already allowed by V14).
INSERT INTO users (id, first_name, middle_name, last_name, email, phone, pin_hash, status, created_at, updated_at)
VALUES ('11111111-1111-1111-1111-111111111111', 'System', '', 'Treasury',
        'system@internal.ewallet', '0000000000', NULL, 'BLOCKED', NOW(), NOW());

-- 2. Customer wallets.
UPDATE wallets w
SET user_id = c.user_id,
    wallet_type = 'CUSTOMER_MAIN',
    account_number = fa.account_number,
    balance = fa.current_balance
FROM customers c, financial_accounts fa
WHERE w.customer_id = c.id AND fa.wallet_id = w.id;

-- 3. Agent wallets — today's single wallet row per agent represents float.
UPDATE wallets w
SET user_id = a.user_id,
    wallet_type = 'AGENT_FLOAT',
    account_number = fa.account_number,
    balance = fa.current_balance
FROM agents a, financial_accounts fa
WHERE w.agent_id = a.id AND fa.wallet_id = w.id;

-- 4. New AGENT_COMMISSION wallet per agent (net-new; nothing tracked commission before).
INSERT INTO wallets (id, user_id, wallet_type, wallet_number, account_number, currency, balance, status, version, created_at, updated_at)
SELECT gen_random_uuid(), a.user_id, 'AGENT_COMMISSION', 'COM-' || a.agent_number, 'COM-' || a.agent_number,
       'TZS', 0, 'ACTIVE', 0, NOW(), NOW()
FROM agents a;

-- 5. COMPANY_MASTER_ACCOUNT — reuses company_master_wallet's existing id, so the ledger
--    migration (V28) needs zero remapping for COMPANY_UNALLOCATED/COMPANY_DISTRIBUTED legs.
INSERT INTO wallets (id, user_id, wallet_type, wallet_number, account_number, currency, balance, status, version, external_reference, created_at, updated_at)
SELECT id, '11111111-1111-1111-1111-111111111111', 'COMPANY_MASTER_ACCOUNT',
       'COMPANY-MASTER-WALLET', 'COMPANY-MASTER-ACCOUNT', currency, unallocated_float,
       'ACTIVE', 0, escrow_bank_account_no, NOW(), NOW()
FROM company_master_wallet WHERE singleton_key = 1;

-- 6. REVENUE (net-new; nothing tracked revenue before).
INSERT INTO wallets (id, user_id, wallet_type, wallet_number, account_number, currency, balance, status, version, created_at, updated_at)
VALUES (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'REVENUE',
        'COMPANY-REVENUE-WALLET', 'COMPANY-REVENUE-ACCOUNT', 'TZS', 0, 'ACTIVE', 0, NOW(), NOW());

-- 7. ESCROW_BANK — seeded to unallocated_float + total_distributed_float, i.e. everything
--    ever minted, not 0. That's the real-world invariant: money in the bank == unallocated
--    float + the sum of all agent float balances. Seeding this at 0 would silently break
--    that invariant on day one.
INSERT INTO wallets (id, user_id, wallet_type, wallet_number, account_number, currency, balance, status, version, created_at, updated_at)
SELECT gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'ESCROW_BANK',
       'COMPANY-ESCROW-WALLET', 'COMPANY-ESCROW-ACCOUNT', currency,
       (unallocated_float + total_distributed_float), 'ACTIVE', 0, NOW(), NOW()
FROM company_master_wallet WHERE singleton_key = 1;
