CREATE TABLE audit_logs (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id     UUID REFERENCES users(id),
    action       VARCHAR(60) NOT NULL,
    entity_type  VARCHAR(60) NOT NULL,
    entity_id    UUID,
    before_json  JSONB,
    after_json   JSONB,
    reason       VARCHAR(500),
    ip_address   VARCHAR(45),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_logs_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX idx_audit_logs_actor_created ON audit_logs (actor_id, created_at);
