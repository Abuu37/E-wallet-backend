CREATE TABLE transactions (
    id UUID PRIMARY KEY,
    reference_no VARCHAR(60) NOT NULL UNIQUE,
    sender_wallet_id UUID NOT NULL,
    receiver_wallet_id UUID NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    fee NUMERIC(19,2) NOT NULL DEFAULT 0,
    commission NUMERIC(19,2) NOT NULL DEFAULT 0,
    transaction_type VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_transactions_sender_wallet FOREIGN KEY (sender_wallet_id) REFERENCES wallets(id),
    CONSTRAINT fk_transactions_receiver_wallet FOREIGN KEY (receiver_wallet_id) REFERENCES wallets(id)
);

CREATE INDEX idx_transactions_sender_wallet ON transactions (sender_wallet_id);
CREATE INDEX idx_transactions_receiver_wallet ON transactions (receiver_wallet_id);
