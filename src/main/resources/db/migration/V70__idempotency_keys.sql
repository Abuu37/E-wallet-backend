-- Safe retries for money POSTs. The same key with the same body returns the stored answer;
-- the same key with a different body is a conflict. One key is unique per user.
CREATE TABLE idempotency_keys (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid NOT NULL REFERENCES users(id),
    idem_key      varchar(80) NOT NULL,
    endpoint      varchar(120) NOT NULL,
    request_hash  varchar(64) NOT NULL,
    status        varchar(20) NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')),
    http_status   integer,
    response_json jsonb,
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, idem_key)
);
