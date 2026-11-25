-- V68: readable ids come from sequences. Customer reference CU-000123, agent ID AG-10234, staff number ST-0005.
-- They are also written to users.username, so people sign in with phone + PIN and never type a username.
CREATE SEQUENCE IF NOT EXISTS customer_ref_seq START WITH 1;
CREATE SEQUENCE IF NOT EXISTS agent_no_seq START WITH 10001;
CREATE SEQUENCE IF NOT EXISTS staff_no_seq START WITH 1;
