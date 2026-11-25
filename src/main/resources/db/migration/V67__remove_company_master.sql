-- V67: float is issued straight from escrow, so the company master wallet and minting are gone.
-- Every shilling in a wallet is backed by a shilling in escrow, so escrow is reset to the sum of all other
-- wallets and the company master is emptied. COMPANY_MASTER_ACCOUNT and MINT_FLOAT stay in the enums so old
-- rows still read. This is a demo reset: it writes balances directly, with no journal lines.
UPDATE wallets SET balance = 0 WHERE wallet_type = 'COMPANY_MASTER_ACCOUNT';

UPDATE wallets
SET balance = (SELECT COALESCE(SUM(balance), 0) FROM wallets
               WHERE wallet_type IN ('CUSTOMER_MAIN', 'AGENT_FLOAT', 'AGENT_COMMISSION', 'COMMISSION', 'REVENUE', 'PAYMENT_GATEWAY_CLEARING'))
WHERE wallet_type = 'ESCROW_BANK';
