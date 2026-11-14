ALTER TABLE wallets ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE wallets ALTER COLUMN wallet_type SET NOT NULL;
ALTER TABLE wallets ALTER COLUMN account_number SET NOT NULL;
ALTER TABLE wallets ALTER COLUMN balance SET NOT NULL;

ALTER TABLE wallets ADD CONSTRAINT chk_wallets_balance_non_negative CHECK (balance >= 0);
ALTER TABLE wallets ADD CONSTRAINT fk_wallets_user FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE wallets ADD CONSTRAINT uk_wallets_account_number UNIQUE (account_number);

ALTER TABLE wallets DROP CONSTRAINT uk_wallets_customer_id;
ALTER TABLE wallets DROP CONSTRAINT uk_wallets_agent_id;
-- chk_wallets_exactly_one_owner was already dropped in V25 (see its comment there) —
-- dropping it again here would error with "constraint does not exist".
ALTER TABLE wallets DROP CONSTRAINT fk_wallets_customer;
ALTER TABLE wallets DROP CONSTRAINT fk_wallets_agent;
ALTER TABLE wallets DROP COLUMN customer_id;
ALTER TABLE wallets DROP COLUMN agent_id;

CREATE UNIQUE INDEX uk_wallets_user_type ON wallets (user_id, wallet_type);
CREATE UNIQUE INDEX uk_wallets_singleton_company_types
    ON wallets (wallet_type)
    WHERE wallet_type IN ('COMPANY_MASTER_ACCOUNT', 'REVENUE', 'ESCROW_BANK');
