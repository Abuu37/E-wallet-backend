-- Collapse wallets.wallet_number and wallets.account_number into one field.
-- Both held the same value at every creation path (confirmed via a real onboarding
-- response), so wallet_number was pure duplication. account_number survives as the
-- single identifier; Postgres drops its own unique constraint/index on wallet_number
-- automatically as part of DROP COLUMN.
ALTER TABLE wallets DROP COLUMN wallet_number;
