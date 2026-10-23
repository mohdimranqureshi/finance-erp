-- AI output is stored as a proposal; nothing reaches the ledger until a person accepts it, and accepted items are DRAFT documents.
CREATE TABLE ai_proposals (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL REFERENCES companies(id),
 kind VARCHAR(20) NOT NULL CHECK (kind IN ('BILL_EXTRACTION','JOURNAL_DRAFT')),
 status VARCHAR(10) NOT NULL DEFAULT 'PROPOSED' CHECK (status IN ('PROPOSED','ACCEPTED','REJECTED')),
 payload JSONB NOT NULL,
 warnings JSONB NOT NULL DEFAULT '[]'::jsonb,
 blockers JSONB NOT NULL DEFAULT '[]'::jsonb,
 confidence NUMERIC(4,3) CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
 model VARCHAR(100) NOT NULL,
 -- Only a hash and length of the source text are kept; raw documents are not stored.
 input_sha256 CHAR(64) NOT NULL,
 input_chars INTEGER NOT NULL,
 created_by_user_id BIGINT NOT NULL REFERENCES app_users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 decided_by_user_id BIGINT REFERENCES app_users(id),
 decided_at TIMESTAMPTZ,
 result_type VARCHAR(30),
 result_id BIGINT,
 UNIQUE(company_id, id)
);
CREATE INDEX idx_ai_proposals_company_status ON ai_proposals(company_id, status, created_at DESC);
