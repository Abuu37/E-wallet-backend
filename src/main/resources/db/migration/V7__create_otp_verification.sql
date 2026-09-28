CREATE TABLE otp_verification (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    otp_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL,
    verified BOOLEAN NOT NULL DEFAULT false,
    verified_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT uk_otp_verification_user UNIQUE (user_id),
    CONSTRAINT fk_otp_verification_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_otp_user_id ON otp_verification(user_id);
CREATE INDEX idx_otp_expires_at ON otp_verification(expires_at);
