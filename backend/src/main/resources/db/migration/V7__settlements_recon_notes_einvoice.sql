-- Settlements, bank reconciliation, credit/debit notes, e-invoicing metadata and year-end closing.
ALTER TABLE journal_entries ADD COLUMN is_closing BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE companies
  ADD COLUMN address_line VARCHAR(200), ADD COLUMN city VARCHAR(100),
  ADD COLUMN pincode VARCHAR(6) CHECK (pincode IS NULL OR pincode ~ '^[1-9][0-9]{5}$');
ALTER TABLE parties
  ADD COLUMN address_line VARCHAR(200), ADD COLUMN city VARCHAR(100),
  ADD COLUMN pincode VARCHAR(6) CHECK (pincode IS NULL OR pincode ~ '^[1-9][0-9]{5}$');

-- IRN is issued by the Invoice Registration Portal; it is only recorded here.
ALTER TABLE sales_invoices
  ADD COLUMN irn CHAR(64) CHECK (irn IS NULL OR irn ~ '^[0-9a-f]{64}$'),
  ADD COLUMN irn_recorded_at TIMESTAMPTZ;
CREATE UNIQUE INDEX uq_sales_invoice_irn ON sales_invoices(irn) WHERE irn IS NOT NULL;

CREATE TABLE payments (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL REFERENCES companies(id),
 direction VARCHAR(8) NOT NULL CHECK (direction IN ('RECEIPT','PAYMENT')),
 payment_date DATE NOT NULL,
 amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
 tds_amount NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (tds_amount >= 0),
 bank_account_id BIGINT NOT NULL,
 reference VARCHAR(100),
 sales_invoice_id BIGINT,
 purchase_bill_id BIGINT,
 journal_entry_id BIGINT NOT NULL,
 created_by_user_id BIGINT NOT NULL REFERENCES app_users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(company_id, id),
 CHECK ((direction='RECEIPT' AND sales_invoice_id IS NOT NULL AND purchase_bill_id IS NULL)
     OR (direction='PAYMENT' AND purchase_bill_id IS NOT NULL AND sales_invoice_id IS NULL AND tds_amount = 0)),
 FOREIGN KEY(company_id, bank_account_id) REFERENCES accounts(company_id, id),
 FOREIGN KEY(company_id, sales_invoice_id) REFERENCES sales_invoices(company_id, id),
 FOREIGN KEY(company_id, purchase_bill_id) REFERENCES purchase_bills(company_id, id),
 FOREIGN KEY(company_id, journal_entry_id) REFERENCES journal_entries(company_id, id)
);
CREATE INDEX idx_payments_invoice ON payments(company_id, sales_invoice_id);
CREATE INDEX idx_payments_bill ON payments(company_id, purchase_bill_id);

CREATE TABLE adjustment_notes (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL REFERENCES companies(id),
 note_type VARCHAR(16) NOT NULL CHECK (note_type IN ('SALES_CREDIT','PURCHASE_DEBIT')),
 sales_invoice_id BIGINT,
 purchase_bill_id BIGINT,
 note_number VARCHAR(50) NOT NULL,
 note_date DATE NOT NULL,
 reason VARCHAR(300) NOT NULL,
 supply_type VARCHAR(12) NOT NULL CHECK (supply_type IN ('INTRA_STATE','INTER_STATE')),
 taxable_amount NUMERIC(19,4) NOT NULL,
 cgst_amount NUMERIC(19,4) NOT NULL,
 sgst_amount NUMERIC(19,4) NOT NULL,
 igst_amount NUMERIC(19,4) NOT NULL,
 total_amount NUMERIC(19,4) NOT NULL CHECK (total_amount > 0),
 journal_entry_id BIGINT NOT NULL,
 created_by_user_id BIGINT NOT NULL REFERENCES app_users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(company_id, id),
 CHECK ((note_type='SALES_CREDIT' AND sales_invoice_id IS NOT NULL AND purchase_bill_id IS NULL)
     OR (note_type='PURCHASE_DEBIT' AND purchase_bill_id IS NOT NULL AND sales_invoice_id IS NULL)),
 FOREIGN KEY(company_id, sales_invoice_id) REFERENCES sales_invoices(company_id, id),
 FOREIGN KEY(company_id, purchase_bill_id) REFERENCES purchase_bills(company_id, id),
 FOREIGN KEY(company_id, journal_entry_id) REFERENCES journal_entries(company_id, id)
);
CREATE UNIQUE INDEX uq_sales_credit_note_number ON adjustment_notes(company_id, note_number) WHERE note_type='SALES_CREDIT';
CREATE UNIQUE INDEX uq_vendor_note_number ON adjustment_notes(company_id, purchase_bill_id, lower(note_number)) WHERE note_type='PURCHASE_DEBIT';
CREATE INDEX idx_adjustment_notes_invoice ON adjustment_notes(company_id, sales_invoice_id);
CREATE INDEX idx_adjustment_notes_bill ON adjustment_notes(company_id, purchase_bill_id);

CREATE TABLE adjustment_note_lines (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL,
 note_id BIGINT NOT NULL,
 description VARCHAR(300) NOT NULL,
 hsn_sac VARCHAR(8) NOT NULL CHECK (hsn_sac ~ '^([0-9]{4}|[0-9]{6}|[0-9]{8})$'),
 quantity NUMERIC(19,4) NOT NULL CHECK (quantity > 0),
 unit_price NUMERIC(19,4) NOT NULL CHECK (unit_price > 0),
 gst_rate NUMERIC(5,2) NOT NULL CHECK (gst_rate >= 0 AND gst_rate <= 40),
 taxable_amount NUMERIC(19,4) NOT NULL,
 cgst_amount NUMERIC(19,4) NOT NULL,
 sgst_amount NUMERIC(19,4) NOT NULL,
 igst_amount NUMERIC(19,4) NOT NULL,
 account_id BIGINT NOT NULL,
 FOREIGN KEY(company_id, note_id) REFERENCES adjustment_notes(company_id, id),
 FOREIGN KEY(company_id, account_id) REFERENCES accounts(company_id, id)
);

CREATE TABLE bank_statement_lines (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL REFERENCES companies(id),
 bank_account_id BIGINT NOT NULL,
 txn_date DATE NOT NULL,
 description VARCHAR(300) NOT NULL DEFAULT '',
 -- Positive is a deposit, negative is a withdrawal.
 amount NUMERIC(19,4) NOT NULL CHECK (amount <> 0),
 reference VARCHAR(100),
 external_id VARCHAR(100),
 matched_payment_id BIGINT,
 matched_by_user_id BIGINT REFERENCES app_users(id),
 matched_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(company_id, bank_account_id) REFERENCES accounts(company_id, id),
 FOREIGN KEY(company_id, matched_payment_id) REFERENCES payments(company_id, id)
);
CREATE UNIQUE INDEX uq_bank_line_external ON bank_statement_lines(company_id, bank_account_id, external_id) WHERE external_id IS NOT NULL;
CREATE UNIQUE INDEX uq_bank_line_payment ON bank_statement_lines(company_id, matched_payment_id) WHERE matched_payment_id IS NOT NULL;
CREATE INDEX idx_bank_lines_account_date ON bank_statement_lines(company_id, bank_account_id, txn_date);
