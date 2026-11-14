-- V21: Agent-initiated float requests (PENDING -> APPROVED/REJECTED), reviewed by an admin.

CREATE TABLE agent_float_requests (
    id UUID PRIMARY KEY,
    agent_id UUID NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL,
    decided_by UUID,
    decision_note VARCHAR(500),
    decided_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_agent_float_requests_agent FOREIGN KEY (agent_id) REFERENCES agents (id),
    CONSTRAINT fk_agent_float_requests_decided_by FOREIGN KEY (decided_by) REFERENCES users (id),
    CONSTRAINT chk_agent_float_requests_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_agent_float_requests_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_agent_float_requests_agent_id ON agent_float_requests (agent_id);
CREATE INDEX idx_agent_float_requests_status ON agent_float_requests (status);
