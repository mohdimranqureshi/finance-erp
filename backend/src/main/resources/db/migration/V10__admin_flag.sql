ALTER TABLE app_users ADD COLUMN is_admin BOOLEAN NOT NULL DEFAULT FALSE;
-- Existing installs: the first account created is the administrator.
UPDATE app_users SET is_admin = TRUE WHERE id = (SELECT min(id) FROM app_users);
