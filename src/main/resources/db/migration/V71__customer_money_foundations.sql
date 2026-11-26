-- V71: what customer money flows need.

-- 1. Agent till numbers (what a customer types to withdraw at an agent), e.g. 220341.
CREATE SEQUENCE IF NOT EXISTS till_no_seq START WITH 220001;
ALTER TABLE agents ADD COLUMN till_number VARCHAR(10);
UPDATE agents SET till_number = nextval('till_no_seq')::text WHERE till_number IS NULL;
ALTER TABLE agents ALTER COLUMN till_number SET NOT NULL;
ALTER TABLE agents ADD CONSTRAINT uk_agents_till_number UNIQUE (till_number);

-- 2. The 4-digit code a customer shows the agent after withdrawing.
ALTER TABLE transactions ADD COLUMN match_code VARCHAR(8);

-- 3. Commission the pool could not pay yet. It is paid automatically after the next refill.
CREATE TABLE commission_owed (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id       UUID NOT NULL REFERENCES agents(id),
    transaction_id UUID REFERENCES transactions(id),
    amount         NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    paid_at        TIMESTAMPTZ
);
CREATE INDEX idx_commission_owed_unpaid ON commission_owed (created_at) WHERE paid_at IS NULL;

-- 4. Saved recipients.
CREATE TABLE beneficiaries (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id UUID NOT NULL REFERENCES users(id),
    wallet_id     UUID NOT NULL REFERENCES wallets(id),
    nickname      VARCHAR(60),
    last_used_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_user_id, wallet_id)
);

-- 5. Wallet-to-wallet fee bands (customers pay; agents pay nothing).
INSERT INTO tariffs (id, transaction_type, min_amount, max_amount, fee, agent_commission, active)
VALUES (gen_random_uuid(), 'TRANSFER',  1000.00,     9999.99,  100.00, 0.00, true),
       (gen_random_uuid(), 'TRANSFER', 10000.00,    49999.99,  300.00, 0.00, true),
       (gen_random_uuid(), 'TRANSFER', 50000.00, 100000000.00, 600.00, 0.00, true);

-- 6. Permission to withdraw at an agent, for customers.
INSERT INTO permissions (id, name, description, module_reference, created_at, updated_at)
VALUES (gen_random_uuid(), 'WITHDRAW_REQUEST', 'Withdraw cash at an agent with your own PIN',
        (SELECT module_reference FROM permissions WHERE name = 'WALLET_TO_WALLET_TRANSFER'), NOW(), NOW());
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name = 'CUSTOMER' AND p.name = 'WITHDRAW_REQUEST';
