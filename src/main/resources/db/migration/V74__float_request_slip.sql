-- V74: a float request (Path B) carries the photo of the deposit slip.
ALTER TABLE treasury_requests ADD COLUMN slip_file_key VARCHAR(200);
ALTER TABLE treasury_requests ADD COLUMN slip_content_type VARCHAR(60);
