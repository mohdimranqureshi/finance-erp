-- ===== GSTR-2B import and ITC reconciliation (report only; never posts to the ledger) =====
CREATE TABLE gstr2b_imports (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL REFERENCES companies(id),
 return_period CHAR(6) NOT NULL CHECK (return_period ~ '^(0[1-9]|1[0-2])[0-9]{4}$'),
 recipient_gstin VARCHAR(15) NOT NULL,
 source_sha256 CHAR(64) NOT NULL,
 line_count INTEGER NOT NULL CHECK (line_count >= 0),
 skipped_count INTEGER NOT NULL DEFAULT 0 CHECK (skipped_count >= 0),
 imported_by_user_id BIGINT NOT NULL REFERENCES app_users(id),
 imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 reconciled_at TIMESTAMPTZ,
 UNIQUE(company_id, id),
 UNIQUE(company_id, return_period)
);

CREATE TABLE gstr2b_lines (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL,
 import_id BIGINT NOT NULL,
 doc_type VARCHAR(12) NOT NULL CHECK (doc_type IN ('INVOICE','AMENDMENT','CREDIT_NOTE','DEBIT_NOTE')),
 supplier_gstin VARCHAR(15) NOT NULL,
 supplier_name VARCHAR(200),
 invoice_number VARCHAR(50) NOT NULL,
 invoice_date DATE NOT NULL,
 original_invoice_number VARCHAR(50),
 original_invoice_date DATE,
 invoice_value NUMERIC(19,4) NOT NULL,
 taxable_amount NUMERIC(19,4) NOT NULL,
 igst_amount NUMERIC(19,4) NOT NULL,
 cgst_amount NUMERIC(19,4) NOT NULL,
 sgst_amount NUMERIC(19,4) NOT NULL,
 cess_amount NUMERIC(19,4) NOT NULL,
 itc_available BOOLEAN NOT NULL,
 itc_unavailable_reason VARCHAR(100),
 reverse_charge BOOLEAN NOT NULL,
 -- Per-rate taxable values from the portal item rows, e.g. [{"rate":18,"taxable":1000}]
 rate_breakup JSONB NOT NULL DEFAULT '[]'::jsonb,
 CHECK ((doc_type = 'AMENDMENT') = (original_invoice_number IS NOT NULL)),
 UNIQUE(company_id, id),
 FOREIGN KEY(company_id, import_id) REFERENCES gstr2b_imports(company_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_gstr2b_lines_import ON gstr2b_lines(company_id, import_id);

CREATE TABLE gstr2b_recon_items (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL,
 import_id BIGINT NOT NULL,
 line_id BIGINT,
 bill_id BIGINT,
 note_id BIGINT,
 status VARCHAR(20) NOT NULL CHECK (status IN ('MATCHED','MISMATCH','MISSING_IN_BOOKS','MISSING_IN_2B',
   'ITC_NOT_AVAILABLE','REVERSE_CHARGE','AMENDMENT_REVIEW')),
 reasons TEXT NOT NULL DEFAULT '',
 resolution VARCHAR(20) CHECK (resolution IS NULL OR resolution IN ('ACCEPTED','FOLLOW_UP_VENDOR','IGNORED')),
 resolution_note VARCHAR(500),
 resolved_by_user_id BIGINT REFERENCES app_users(id),
 resolved_at TIMESTAMPTZ,
 CHECK (line_id IS NOT NULL OR bill_id IS NOT NULL OR note_id IS NOT NULL),
 CHECK (bill_id IS NULL OR note_id IS NULL),
 FOREIGN KEY(company_id, import_id) REFERENCES gstr2b_imports(company_id, id) ON DELETE CASCADE,
 FOREIGN KEY(company_id, line_id) REFERENCES gstr2b_lines(company_id, id) ON DELETE CASCADE,
 FOREIGN KEY(company_id, bill_id) REFERENCES purchase_bills(company_id, id),
 FOREIGN KEY(company_id, note_id) REFERENCES adjustment_notes(company_id, id)
);
CREATE INDEX idx_gstr2b_recon_import ON gstr2b_recon_items(company_id, import_id, status);
-- A bill or debit note can be matched to a portal document in only one return period.
CREATE UNIQUE INDEX uq_gstr2b_recon_matched_bill ON gstr2b_recon_items(company_id, bill_id)
  WHERE bill_id IS NOT NULL AND status <> 'MISSING_IN_2B';
CREATE UNIQUE INDEX uq_gstr2b_recon_matched_note ON gstr2b_recon_items(company_id, note_id)
  WHERE note_id IS NOT NULL AND status <> 'MISSING_IN_2B';
