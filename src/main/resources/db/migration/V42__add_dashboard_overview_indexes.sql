CREATE INDEX IF NOT EXISTS idx_transactions_status_created_at ON transactions (status, created_at);
CREATE INDEX IF NOT EXISTS idx_wallets_wallet_type ON wallets (wallet_type);
CREATE INDEX IF NOT EXISTS idx_treasury_requests_status ON treasury_requests (status);
