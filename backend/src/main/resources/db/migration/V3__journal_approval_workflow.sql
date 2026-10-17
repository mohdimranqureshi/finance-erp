-- Add explicit maker-checker workflow for new and existing journal entries.
ALTER TABLE journal_entries DROP CONSTRAINT IF EXISTS journal_entries_status_check;
ALTER TABLE journal_entries ADD CONSTRAINT journal_entries_status_check
  CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','POSTED','REVERSED'));

ALTER TABLE journal_entries
  ADD COLUMN created_by_user_id BIGINT REFERENCES app_users(id),
  ADD COLUMN submitted_by_user_id BIGINT REFERENCES app_users(id),
  ADD COLUMN submitted_at TIMESTAMPTZ,
  ADD COLUMN approved_by_user_id BIGINT REFERENCES app_users(id),
  ADD COLUMN approved_at TIMESTAMPTZ,
  ADD COLUMN posted_by_user_id BIGINT REFERENCES app_users(id);

CREATE INDEX idx_journals_approval_queue
  ON journal_entries(company_id, status, submitted_at DESC);
CREATE INDEX idx_audit_company_created ON audit_events(company_id, created_at DESC);

-- Keep historic records readable; only new workflow transitions require these fields.
