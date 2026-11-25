-- Permission to reset an agent's PIN (module of the other user permissions), granted to ADMIN.
INSERT INTO permissions (id, name, description, module_reference, created_at, updated_at)
VALUES (gen_random_uuid(), 'AGENT_MANAGE', 'Reset an agent PIN',
        (SELECT module_reference FROM permissions WHERE name = 'USER_UPDATE'), NOW(), NOW());

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.name = 'ADMIN' AND p.name = 'AGENT_MANAGE';
