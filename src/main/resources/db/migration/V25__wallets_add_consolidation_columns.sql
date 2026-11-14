ALTER TABLE wallets
    ADD COLUMN user_id UUID,
    ADD COLUMN wallet_type VARCHAR(30),
    ADD COLUMN account_number VARCHAR(30),
    ADD COLUMN balance NUMERIC(19,2),
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN external_reference VARCHAR(100);

-- Dropped here, not in V27: V26's net-new company-level/commission wallet rows set
-- neither customer_id nor agent_id (ownership is moving to user_id alone), which this
-- V15 constraint forbids. It must be gone before V26 runs, not after.
ALTER TABLE wallets DROP CONSTRAINT chk_wallets_exactly_one_owner;
