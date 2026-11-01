ALTER TABLE ai_proposals DROP CONSTRAINT ai_proposals_kind_check;
ALTER TABLE ai_proposals ADD CONSTRAINT ai_proposals_kind_check CHECK (kind IN ('BILL_EXTRACTION','SALES_EXTRACTION','JOURNAL_DRAFT'));
