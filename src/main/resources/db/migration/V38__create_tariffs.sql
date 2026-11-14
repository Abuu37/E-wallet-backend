CREATE TABLE tariffs (
    id UUID PRIMARY KEY,
    reference UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    transaction_type VARCHAR(30) NOT NULL,
    min_amount NUMERIC(19,2) NOT NULL,
    max_amount NUMERIC(19,2) NOT NULL,
    fee NUMERIC(19,2) NOT NULL,
    agent_commission NUMERIC(19,2) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_tariffs_amount_range CHECK (max_amount >= min_amount)
);
