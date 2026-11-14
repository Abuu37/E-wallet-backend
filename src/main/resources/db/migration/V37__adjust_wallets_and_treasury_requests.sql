ALTER TABLE wallets DROP COLUMN external_reference;
ALTER TABLE wallets
    ADD COLUMN reference UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    ADD COLUMN account_name VARCHAR(150) NOT NULL DEFAULT '';
ALTER TABLE wallets ALTER COLUMN account_name DROP DEFAULT;

ALTER TABLE treasury_requests ADD COLUMN external_reference VARCHAR(100);
