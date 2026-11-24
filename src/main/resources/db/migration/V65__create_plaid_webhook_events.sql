CREATE TABLE plaid_webhook_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    webhook_type VARCHAR(60) NOT NULL,
    webhook_code VARCHAR(60) NOT NULL,
    provider_ref VARCHAR(100),
    body_sha256 VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    processed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_plaid_webhook_events_body UNIQUE (body_sha256)
);
CREATE INDEX idx_plaid_webhook_events_provider_ref ON plaid_webhook_events (provider_ref);
