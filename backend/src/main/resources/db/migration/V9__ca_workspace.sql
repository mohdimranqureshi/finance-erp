-- ===== CA multi-client workspace: per-user client preferences (presentation only; no accounting effect) =====
CREATE TABLE workspace_client_settings (
 -- Cascade so that deleting a user with no accounting footprint is not blocked by their preferences.
 user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 company_id BIGINT NOT NULL REFERENCES companies(id),
 pinned BOOLEAN NOT NULL DEFAULT FALSE,
 label VARCHAR(80),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(user_id, company_id)
);
