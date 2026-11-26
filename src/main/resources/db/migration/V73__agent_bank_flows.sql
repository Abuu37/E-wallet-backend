-- V73: agents can link a business bank, top up float from it, and send float or commission back to it.
INSERT INTO permissions (id, name, description, module_reference, created_at, updated_at)
VALUES (gen_random_uuid(), 'AGENT_BANK', 'Link a business bank, top up float from it and send float or commission to it',
        (SELECT module_reference FROM permissions WHERE name = 'AGENT_VIEW'), NOW(), NOW());
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name = 'AGENT' AND p.name = 'AGENT_BANK';
