-- Session authentication, TOTP MFA and session revocation.
-- app_users.active (V1) is the account disabled flag; no separate column is needed.

ALTER TABLE app_users
	ADD COLUMN mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE,
	-- AES-256-GCM ciphertext ("v1:" + base64(iv || ciphertext || tag)); never the raw secret.
	ADD COLUMN mfa_secret_enc VARCHAR(255),
	ADD COLUMN mfa_pending_secret_enc VARCHAR(255),
	ADD COLUMN mfa_pending_created_at TIMESTAMPTZ,
	ADD COLUMN mfa_enabled_at TIMESTAMPTZ,
	-- Highest RFC 6238 time step accepted so far; codes for this or an earlier step are rejected (replay protection).
	ADD COLUMN mfa_last_used_step BIGINT,
	-- Incremented to revoke every existing browser session of the user (disable, MFA change/reset).
	ADD COLUMN session_version INTEGER NOT NULL DEFAULT 0;

ALTER TABLE app_users ADD CONSTRAINT app_users_mfa_secret_present
	CHECK (NOT mfa_enabled OR mfa_secret_enc IS NOT NULL);

-- One-time recovery codes. Only a SHA-256 hash is stored; each code is single use.
CREATE TABLE user_mfa_recovery_codes (
	id BIGSERIAL PRIMARY KEY,
	user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
	code_hash CHAR(64) NOT NULL,
	used_at TIMESTAMPTZ,
	created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
	UNIQUE (user_id, code_hash)
);
CREATE INDEX user_mfa_recovery_codes_unused ON user_mfa_recovery_codes(user_id) WHERE used_at IS NULL;
