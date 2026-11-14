ALTER TABLE agents
    ADD COLUMN reference UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    ADD COLUMN first_name VARCHAR(100) NOT NULL,
    ADD COLUMN middle_name VARCHAR(100),
    ADD COLUMN last_name VARCHAR(100) NOT NULL,
    ADD COLUMN email VARCHAR(255) NOT NULL,
    ADD COLUMN phone_number VARCHAR(30) NOT NULL,
    ADD COLUMN business_name VARCHAR(150) NOT NULL,
    ADD COLUMN tin_number VARCHAR(30) NOT NULL,
    ADD COLUMN agent_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN created_by UUID;

ALTER TABLE agents ADD CONSTRAINT fk_agents_created_by FOREIGN KEY (created_by) REFERENCES users(id);
