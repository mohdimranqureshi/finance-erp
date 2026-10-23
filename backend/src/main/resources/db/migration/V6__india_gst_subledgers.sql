-- India GST profile, parties, and GST sales/purchase sub-ledgers. Ledger postings still go through the maker-checker journal workflow.
ALTER TABLE companies
  ADD COLUMN gstin VARCHAR(15) CHECK (gstin IS NULL OR gstin ~ '^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$'),
  ADD COLUMN pan VARCHAR(10) CHECK (pan IS NULL OR pan ~ '^[A-Z]{5}[0-9]{4}[A-Z]$'),
  ADD COLUMN state_code CHAR(2) CHECK (state_code IS NULL OR state_code ~ '^[0-9]{2}$');

ALTER TABLE accounts ADD COLUMN system_key VARCHAR(40);
CREATE UNIQUE INDEX uq_accounts_system_key ON accounts(company_id, system_key) WHERE system_key IS NOT NULL;

CREATE TABLE parties (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL REFERENCES companies(id),
 party_type VARCHAR(10) NOT NULL CHECK (party_type IN ('CUSTOMER','VENDOR')),
 name VARCHAR(180) NOT NULL,
 gstin VARCHAR(15) CHECK (gstin IS NULL OR gstin ~ '^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$'),
 pan VARCHAR(10) CHECK (pan IS NULL OR pan ~ '^[A-Z]{5}[0-9]{4}[A-Z]$'),
 state_code CHAR(2) NOT NULL CHECK (state_code ~ '^[0-9]{2}$'),
 email VARCHAR(254),
 active BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(company_id, id)
);
CREATE UNIQUE INDEX uq_party_gstin ON parties(company_id, party_type, gstin) WHERE gstin IS NOT NULL;

-- Gapless per-financial-year numbering; rows are locked by the upsert that increments them.
CREATE TABLE document_sequences (
 company_id BIGINT NOT NULL REFERENCES companies(id),
 doc_type VARCHAR(20) NOT NULL,
 fiscal_year VARCHAR(7) NOT NULL,
 last_number INTEGER NOT NULL DEFAULT 0,
 PRIMARY KEY(company_id, doc_type, fiscal_year)
);

CREATE TABLE sales_invoices (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL REFERENCES companies(id),
 customer_id BIGINT NOT NULL,
 invoice_number VARCHAR(16),
 invoice_date DATE NOT NULL,
 due_date DATE,
 place_of_supply CHAR(2) NOT NULL,
 supply_type VARCHAR(12) NOT NULL CHECK (supply_type IN ('INTRA_STATE','INTER_STATE')),
 taxable_amount NUMERIC(19,4) NOT NULL,
 cgst_amount NUMERIC(19,4) NOT NULL,
 sgst_amount NUMERIC(19,4) NOT NULL,
 igst_amount NUMERIC(19,4) NOT NULL,
 total_amount NUMERIC(19,4) NOT NULL CHECK (total_amount > 0),
 status VARCHAR(12) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','ISSUED')),
 journal_entry_id BIGINT,
 created_by_user_id BIGINT NOT NULL REFERENCES app_users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 issued_at TIMESTAMPTZ,
 UNIQUE(company_id, id),
 UNIQUE(company_id, invoice_number),
 FOREIGN KEY(company_id, customer_id) REFERENCES parties(company_id, id),
 FOREIGN KEY(company_id, journal_entry_id) REFERENCES journal_entries(company_id, id)
);
CREATE INDEX idx_sales_invoices_company_date ON sales_invoices(company_id, invoice_date);

CREATE TABLE sales_invoice_lines (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL,
 invoice_id BIGINT NOT NULL,
 description VARCHAR(300) NOT NULL,
 hsn_sac VARCHAR(8) NOT NULL CHECK (hsn_sac ~ '^([0-9]{4}|[0-9]{6}|[0-9]{8})$'),
 quantity NUMERIC(19,4) NOT NULL CHECK (quantity > 0),
 unit_price NUMERIC(19,4) NOT NULL CHECK (unit_price >= 0),
 gst_rate NUMERIC(5,2) NOT NULL CHECK (gst_rate >= 0 AND gst_rate <= 40),
 taxable_amount NUMERIC(19,4) NOT NULL,
 cgst_amount NUMERIC(19,4) NOT NULL,
 sgst_amount NUMERIC(19,4) NOT NULL,
 igst_amount NUMERIC(19,4) NOT NULL,
 revenue_account_id BIGINT NOT NULL,
 FOREIGN KEY(company_id, invoice_id) REFERENCES sales_invoices(company_id, id),
 FOREIGN KEY(company_id, revenue_account_id) REFERENCES accounts(company_id, id)
);
CREATE INDEX idx_sales_invoice_lines_invoice ON sales_invoice_lines(company_id, invoice_id);

CREATE TABLE purchase_bills (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL REFERENCES companies(id),
 vendor_id BIGINT NOT NULL,
 vendor_invoice_number VARCHAR(50) NOT NULL,
 bill_date DATE NOT NULL,
 due_date DATE,
 place_of_supply CHAR(2) NOT NULL,
 supply_type VARCHAR(12) NOT NULL CHECK (supply_type IN ('INTRA_STATE','INTER_STATE')),
 taxable_amount NUMERIC(19,4) NOT NULL,
 cgst_amount NUMERIC(19,4) NOT NULL,
 sgst_amount NUMERIC(19,4) NOT NULL,
 igst_amount NUMERIC(19,4) NOT NULL,
 total_amount NUMERIC(19,4) NOT NULL CHECK (total_amount > 0),
 itc_eligible BOOLEAN NOT NULL,
 tds_section VARCHAR(10),
 tds_rate NUMERIC(5,2) CHECK (tds_rate IS NULL OR (tds_rate > 0 AND tds_rate <= 30)),
 tds_amount NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (tds_amount >= 0),
 status VARCHAR(12) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','BOOKED')),
 journal_entry_id BIGINT,
 created_by_user_id BIGINT NOT NULL REFERENCES app_users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 booked_at TIMESTAMPTZ,
 UNIQUE(company_id, id),
 UNIQUE(company_id, vendor_id, vendor_invoice_number),
 FOREIGN KEY(company_id, vendor_id) REFERENCES parties(company_id, id),
 FOREIGN KEY(company_id, journal_entry_id) REFERENCES journal_entries(company_id, id)
);
CREATE INDEX idx_purchase_bills_company_date ON purchase_bills(company_id, bill_date);

CREATE TABLE purchase_bill_lines (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL,
 bill_id BIGINT NOT NULL,
 description VARCHAR(300) NOT NULL,
 hsn_sac VARCHAR(8) NOT NULL CHECK (hsn_sac ~ '^([0-9]{4}|[0-9]{6}|[0-9]{8})$'),
 quantity NUMERIC(19,4) NOT NULL CHECK (quantity > 0),
 unit_price NUMERIC(19,4) NOT NULL CHECK (unit_price >= 0),
 gst_rate NUMERIC(5,2) NOT NULL CHECK (gst_rate >= 0 AND gst_rate <= 40),
 taxable_amount NUMERIC(19,4) NOT NULL,
 cgst_amount NUMERIC(19,4) NOT NULL,
 sgst_amount NUMERIC(19,4) NOT NULL,
 igst_amount NUMERIC(19,4) NOT NULL,
 expense_account_id BIGINT NOT NULL,
 FOREIGN KEY(company_id, bill_id) REFERENCES purchase_bills(company_id, id),
 FOREIGN KEY(company_id, expense_account_id) REFERENCES accounts(company_id, id)
);
CREATE INDEX idx_purchase_bill_lines_bill ON purchase_bill_lines(company_id, bill_id);
