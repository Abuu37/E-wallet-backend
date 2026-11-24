ALTER TABLE user_sessions ADD COLUMN family_id UUID;
UPDATE user_sessions SET family_id = id WHERE family_id IS NULL;
ALTER TABLE user_sessions ALTER COLUMN family_id SET NOT NULL;
CREATE INDEX idx_user_sessions_family_id ON user_sessions(family_id);
