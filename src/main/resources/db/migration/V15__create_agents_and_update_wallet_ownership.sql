
CREATE TABLE agents (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    agent_number VARCHAR(30) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    update_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT  uk_agents_user_id UNIQUE (user_id),
    CONSTRAINT uk_agents_agent_number UNIQUE (agent_number),
    CONSTRAINT fk_agents_user FOREIGN KEY (user_id) REFERENCES users(id)
);

-- 2. Allow wallets to belong to a customer OR an agent
ALTER TABLE wallets ALTER COLUMN customer_id DROP NOT NULL;

-- 3. Add agent ownership
ALTER TABLE wallets ADD COLUMN agent_id UUID;

-- 4. Ensure one wallet per agent
ALTER TABLE wallets ADD CONSTRAINT uk_wallets_agent_id UNIQUE (agent_id);

-- 5. Link wallet to agent
ALTER TABLE wallets ADD CONSTRAINT fk_wallets_agent FOREIGN KEY (agent_id) REFERENCES agents (id);

-- 6. Ensure every wallet has exactly one owner
ALTER TABLE wallets ADD  CONSTRAINT chk_wallets_exactly_one_owner
CHECK (
    (customer_id IS NOT NULL AND agent_id IS NULL)
        OR
    (customer_id IS NULL AND agent_id IS NOT NULL)
    );
