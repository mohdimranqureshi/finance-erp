-- ===== TDS deposits (challans), their allocation to deductions, and the deductor's TAN for Form 26Q =====
ALTER TABLE companies ADD COLUMN tan VARCHAR(10) CHECK (tan IS NULL OR tan ~ '^[A-Z]{4}[0-9]{5}[A-Z]$');

CREATE TABLE tds_challans (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL REFERENCES companies(id),
 bsr_code CHAR(7) NOT NULL CHECK (bsr_code ~ '^[0-9]{7}$'),
 challan_serial VARCHAR(5) NOT NULL CHECK (challan_serial ~ '^[0-9]{1,5}$'),
 deposit_date DATE NOT NULL,
 tax_amount NUMERIC(19,4) NOT NULL CHECK (tax_amount >= 0),
 interest_amount NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (interest_amount >= 0),
 fee_amount NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (fee_amount >= 0),
 bank_account_id BIGINT NOT NULL,
 journal_entry_id BIGINT NOT NULL,
 created_by_user_id BIGINT NOT NULL REFERENCES app_users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CHECK (tax_amount + interest_amount + fee_amount > 0),
 UNIQUE(company_id, id),
 UNIQUE(company_id, bsr_code, deposit_date, challan_serial),
 FOREIGN KEY(company_id, bank_account_id) REFERENCES accounts(company_id, id),
 FOREIGN KEY(company_id, journal_entry_id) REFERENCES journal_entries(company_id, id)
);

CREATE TABLE tds_challan_allocations (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL,
 challan_id BIGINT NOT NULL,
 bill_id BIGINT NOT NULL,
 amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
 created_by_user_id BIGINT NOT NULL REFERENCES app_users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(company_id, challan_id, bill_id),
 FOREIGN KEY(company_id, challan_id) REFERENCES tds_challans(company_id, id),
 FOREIGN KEY(company_id, bill_id) REFERENCES purchase_bills(company_id, id)
);
CREATE INDEX idx_tds_allocations_bill ON tds_challan_allocations(company_id, bill_id);
