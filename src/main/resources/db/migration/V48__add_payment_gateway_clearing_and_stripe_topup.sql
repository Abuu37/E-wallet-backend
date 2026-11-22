-- Extend the singleton-wallet-type constraint (same pattern as V45).
DROP INDEX uk_wallets_singleton_company_types;
CREATE UNIQUE INDEX uk_wallets_singleton_company_types
    ON wallets (wallet_type)
    WHERE wallet_type IN ('COMPANY_MASTER_ACCOUNT', 'REVENUE', 'ESCROW_BANK', 'COMMISSION', 'PAYMENT_GATEWAY_CLEARING');

-- Seed the singleton wallet, owned by the same system user as the other
-- company-level wallets (seeded in V26).
-- NOTE: account_number shortened from the plan's literal ('COMPANY-GATEWAY-CLEARING-ACCOUNT',
-- 32 chars) to fit the existing wallets.account_number VARCHAR(30) column (see V25); the
-- 32-char value would fail with "value too long for type character varying(30)".
INSERT INTO wallets (id, user_id, wallet_type, account_number, currency, balance, status, version, account_name, created_at, updated_at)
VALUES (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'PAYMENT_GATEWAY_CLEARING',
        'COMPANY-CLEARING-ACCOUNT', 'TZS', 0, 'ACTIVE', 0, 'Payment Gateway Clearing', NOW(), NOW());

-- External gateway reference, for webhook idempotency and reconciliation.
ALTER TABLE transactions ADD COLUMN external_reference VARCHAR(100);
CREATE UNIQUE INDEX uk_transactions_external_reference
    ON transactions (external_reference)
    WHERE external_reference IS NOT NULL;

-- New tariff band: no agent commission, since no agent is involved.
INSERT INTO tariffs (id, transaction_type, min_amount, max_amount, fee, agent_commission, active)
VALUES (gen_random_uuid(), 'TOPUP_STRIPE', 1000.00, 100000000.00, 0.00, 0.00, true);
