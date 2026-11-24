ALTER TABLE customers
    ADD COLUMN kyc_status VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED',
    ADD COLUMN kyc_provider_ref VARCHAR(100),
    ADD COLUMN kyc_verified_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE customers ADD CONSTRAINT chk_customers_kyc_status CHECK (kyc_status IN ('NOT_STARTED', 'PENDING', 'VERIFIED', 'FAILED'));
