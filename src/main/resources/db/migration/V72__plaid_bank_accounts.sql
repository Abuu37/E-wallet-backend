-- V72: banks linked through Plaid, and the bank movements that use them.

-- 1. A bank account a person linked. The Plaid access token is stored encrypted (AES-GCM), never in clear.
CREATE TABLE linked_bank_accounts (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                UUID NOT NULL REFERENCES users(id),
    provider               VARCHAR(20) NOT NULL DEFAULT 'PLAID',
    provider_item_id       VARCHAR(100) NOT NULL,
    access_token_encrypted TEXT NOT NULL,
    provider_account_id    VARCHAR(100) NOT NULL,
    institution_name       VARCHAR(150),
    account_name           VARCHAR(150),
    account_mask           VARCHAR(10),
    holder_name            VARCHAR(200),
    name_matched           BOOLEAN NOT NULL DEFAULT FALSE,
    status                 VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, provider_account_id)
);
CREATE INDEX idx_linked_bank_accounts_user ON linked_bank_accounts (user_id);

-- 2. Which bank a transaction used, and how to describe it on a receipt.
ALTER TABLE transactions ADD COLUMN linked_bank_id UUID REFERENCES linked_bank_accounts(id);
ALTER TABLE transactions ADD COLUMN counterparty_label VARCHAR(150);

-- 3. The matching line to post on the user's Sandbox bank. Kept as a row so a failed call is retried and never lost.
CREATE TABLE bank_postings (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transactions(id),
    linked_bank_id UUID NOT NULL REFERENCES linked_bank_accounts(id),
    direction      VARCHAR(10) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    usd_amount     NUMERIC(19, 2) NOT NULL CHECK (usd_amount > 0),
    status         VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DONE', 'FAILED')),
    attempts       INT NOT NULL DEFAULT 0,
    last_error     VARCHAR(200),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    done_at        TIMESTAMPTZ
);
CREATE INDEX idx_bank_postings_pending ON bank_postings (status, created_at);

-- 4. Withdrawing to your own bank costs a flat 1,000 TZS.
INSERT INTO tariffs (id, transaction_type, min_amount, max_amount, fee, agent_commission, active)
VALUES (gen_random_uuid(), 'BANK_WITHDRAWAL', 1000.00, 100000000.00, 1000.00, 0.00, true);

-- 5. Permission for customers to add money from their own bank.
INSERT INTO permissions (id, name, description, module_reference, created_at, updated_at)
VALUES (gen_random_uuid(), 'BANK_TOPUP', 'Add money from your own linked bank',
        (SELECT module_reference FROM permissions WHERE name = 'WALLET_TO_WALLET_TRANSFER'), NOW(), NOW());
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name = 'CUSTOMER' AND p.name = 'BANK_TOPUP';
