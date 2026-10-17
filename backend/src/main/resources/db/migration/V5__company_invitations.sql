CREATE TABLE company_invitations (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL REFERENCES companies(id),
 email VARCHAR(254) NOT NULL,
 role VARCHAR(30) NOT NULL CHECK (role IN ('ACCOUNTANT','APPROVER','VIEWER')),
 token_hash CHAR(64) NOT NULL UNIQUE,
 expires_at TIMESTAMPTZ NOT NULL,
 created_by_user_id BIGINT NOT NULL REFERENCES app_users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 accepted_at TIMESTAMPTZ,
 accepted_by_user_id BIGINT REFERENCES app_users(id)
);
CREATE INDEX idx_invitations_company_email ON company_invitations(company_id, lower(email), expires_at);
CREATE UNIQUE INDEX uq_pending_invitation_company_email ON company_invitations(company_id, lower(email)) WHERE accepted_at IS NULL;
