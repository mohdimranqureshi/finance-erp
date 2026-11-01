-- ===== Core ledger =====
CREATE TABLE companies (
 id BIGSERIAL PRIMARY KEY,
 name VARCHAR(180) NOT NULL,
 legal_name VARCHAR(240),
 base_currency CHAR(3) NOT NULL DEFAULT 'INR' CHECK (base_currency ~ '^[A-Z]{3}$'),
 status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE accounts (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL REFERENCES companies(id),
 code VARCHAR(30) NOT NULL,
 name VARCHAR(180) NOT NULL,
 account_type VARCHAR(20) NOT NULL CHECK (account_type IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE')),
 parent_account_id BIGINT,
 active BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(company_id, code), UNIQUE(company_id,id),
 FOREIGN KEY(company_id,parent_account_id) REFERENCES accounts(company_id,id)
);
CREATE TABLE journal_entries (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL REFERENCES companies(id),
 journal_date DATE NOT NULL,
 description VARCHAR(500) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','POSTED','REVERSED')),
 idempotency_key VARCHAR(100),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), posted_at TIMESTAMPTZ,
 UNIQUE(company_id,id), UNIQUE(company_id,idempotency_key)
);
CREATE INDEX idx_journal_company_date ON journal_entries(company_id,journal_date);
CREATE TABLE journal_lines (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL,
 journal_entry_id BIGINT NOT NULL,
 account_id BIGINT NOT NULL,
 description VARCHAR(500),
 debit NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (debit >= 0),
 credit NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (credit >= 0),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(company_id,journal_entry_id) REFERENCES journal_entries(company_id,id),
 FOREIGN KEY(company_id,account_id) REFERENCES accounts(company_id,id),
 CHECK ((debit > 0 AND credit = 0) OR (credit > 0 AND debit = 0))
);
CREATE INDEX idx_journal_lines_entry ON journal_lines(company_id,journal_entry_id);
CREATE INDEX idx_journal_lines_account ON journal_lines(company_id,account_id);
CREATE TABLE audit_events (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT REFERENCES companies(id),
 actor VARCHAR(180) NOT NULL DEFAULT 'LOCAL_DEV',
 action VARCHAR(80) NOT NULL,
 entity_type VARCHAR(80) NOT NULL,
 entity_id VARCHAR(100),
 details JSONB NOT NULL DEFAULT '{}'::jsonb,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ===== Users, memberships and periods =====
CREATE TABLE app_users (
 id BIGSERIAL PRIMARY KEY,
 email VARCHAR(254) NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL,
 display_name VARCHAR(180) NOT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 is_admin BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE company_memberships (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL REFERENCES companies(id),
 user_id BIGINT NOT NULL REFERENCES app_users(id),
 role VARCHAR(30) NOT NULL CHECK (role IN ('OWNER','ACCOUNTANT','APPROVER','VIEWER')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(company_id,user_id)
);
CREATE INDEX idx_membership_user ON company_memberships(user_id,company_id);
CREATE TABLE accounting_periods (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL REFERENCES companies(id),
 name VARCHAR(80) NOT NULL,
 start_date DATE NOT NULL,
 end_date DATE NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED')),
 closed_at TIMESTAMPTZ,
 closed_by BIGINT REFERENCES app_users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CHECK (start_date <= end_date),
 UNIQUE(company_id,name),
 UNIQUE(company_id,start_date,end_date)
);
ALTER TABLE journal_entries ADD COLUMN reversed_by_journal_id BIGINT;
ALTER TABLE journal_entries ADD COLUMN reversal_of_journal_id BIGINT;
ALTER TABLE journal_entries ADD CONSTRAINT fk_journal_reversed_by FOREIGN KEY(company_id,reversed_by_journal_id) REFERENCES journal_entries(company_id,id);
ALTER TABLE journal_entries ADD CONSTRAINT fk_journal_reversal_of FOREIGN KEY(company_id,reversal_of_journal_id) REFERENCES journal_entries(company_id,id);
CREATE UNIQUE INDEX uq_single_reversal_per_journal ON journal_entries(company_id,reversal_of_journal_id) WHERE reversal_of_journal_id IS NOT NULL;

-- ===== Journal maker-checker workflow =====
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

-- ===== Immutability guardrails =====
-- Posted ledger entries/lines and audit events are append-only.
-- Database owners can still disable triggers; production DB credentials must be least-privilege.
CREATE OR REPLACE FUNCTION prevent_posted_journal_mutation() RETURNS trigger AS $$
DECLARE old_status VARCHAR(20);
BEGIN
  IF TG_OP = 'INSERT' THEN RETURN NEW; END IF;
  SELECT status INTO old_status FROM journal_entries WHERE company_id=OLD.company_id AND id=OLD.id;
  IF old_status IN ('POSTED','REVERSED') THEN
    IF TG_OP = 'UPDATE'
       AND (to_jsonb(NEW) - 'reversed_by_journal_id') = (to_jsonb(OLD) - 'reversed_by_journal_id') THEN
      RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Posted journal entries are immutable; create a reversal instead.' USING ERRCODE='55000';
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_posted_journal_immutable
BEFORE UPDATE OR DELETE ON journal_entries
FOR EACH ROW EXECUTE FUNCTION prevent_posted_journal_mutation();

CREATE OR REPLACE FUNCTION prevent_posted_journal_line_mutation() RETURNS trigger AS $$
DECLARE old_status VARCHAR(20);
DECLARE new_status VARCHAR(20);
BEGIN
  IF TG_OP IN ('UPDATE','DELETE') THEN
    SELECT status INTO old_status FROM journal_entries WHERE company_id=OLD.company_id AND id=OLD.journal_entry_id;
    IF old_status IN ('POSTED','REVERSED') THEN
      RAISE EXCEPTION 'Lines of posted journals are immutable; create a reversal instead.' USING ERRCODE='55000';
    END IF;
  END IF;
  IF TG_OP IN ('INSERT','UPDATE') THEN
    SELECT status INTO new_status FROM journal_entries WHERE company_id=NEW.company_id AND id=NEW.journal_entry_id;
    IF new_status IN ('POSTED','REVERSED') THEN
      RAISE EXCEPTION 'Lines of posted journals are immutable; create a reversal instead.' USING ERRCODE='55000';
    END IF;
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_posted_journal_lines_immutable
BEFORE INSERT OR UPDATE OR DELETE ON journal_lines
FOR EACH ROW EXECUTE FUNCTION prevent_posted_journal_line_mutation();

CREATE OR REPLACE FUNCTION prevent_audit_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'Audit events are append-only.' USING ERRCODE='55000';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_audit_events_immutable
BEFORE UPDATE OR DELETE ON audit_events
FOR EACH ROW EXECUTE FUNCTION prevent_audit_mutation();

-- ===== Company invitations =====
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

-- ===== India GST profile, parties, and GST sales/purchase sub-ledgers =====
-- Ledger postings still go through the maker-checker journal workflow.
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

-- ===== Settlements, bank reconciliation, credit/debit notes, e-invoicing metadata and year-end closing =====
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

-- A statement line can be explained by an accepted AI-categorised journal (bank charges, interest) instead of a payment.
ALTER TABLE bank_statement_lines ADD COLUMN journal_entry_id BIGINT REFERENCES journal_entries(id);
CREATE INDEX idx_bank_lines_journal ON bank_statement_lines(journal_entry_id) WHERE journal_entry_id IS NOT NULL;

-- ===== Revenue recognition schedules =====
-- Invoice revenue is deferred, then recognised monthly through draft journals.
CREATE TABLE revenue_schedules (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL REFERENCES companies(id),
 sales_invoice_id BIGINT NOT NULL,
 description VARCHAR(300) NOT NULL,
 total_amount NUMERIC(19,4) NOT NULL CHECK (total_amount > 0),
 start_date DATE NOT NULL,
 months INTEGER NOT NULL CHECK (months BETWEEN 1 AND 120),
 revenue_account_id BIGINT NOT NULL,
 deferred_account_id BIGINT NOT NULL,
 deferral_journal_id BIGINT NOT NULL,
 created_by_user_id BIGINT NOT NULL REFERENCES app_users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(company_id, id),
 FOREIGN KEY(company_id, sales_invoice_id) REFERENCES sales_invoices(company_id, id),
 FOREIGN KEY(company_id, revenue_account_id) REFERENCES accounts(company_id, id),
 FOREIGN KEY(company_id, deferred_account_id) REFERENCES accounts(company_id, id),
 FOREIGN KEY(company_id, deferral_journal_id) REFERENCES journal_entries(company_id, id)
);
CREATE INDEX idx_revenue_schedules_invoice ON revenue_schedules(company_id, sales_invoice_id);

CREATE TABLE revenue_schedule_entries (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL,
 schedule_id BIGINT NOT NULL,
 recognition_date DATE NOT NULL,
 amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
 journal_entry_id BIGINT,
 UNIQUE(company_id, id),
 UNIQUE(company_id, schedule_id, recognition_date),
 FOREIGN KEY(company_id, schedule_id) REFERENCES revenue_schedules(company_id, id),
 FOREIGN KEY(company_id, journal_entry_id) REFERENCES journal_entries(company_id, id)
);
CREATE INDEX idx_revenue_entries_due ON revenue_schedule_entries(company_id, recognition_date) WHERE journal_entry_id IS NULL;

-- ===== AI proposals =====
-- AI output is stored as a proposal; nothing reaches the ledger until a person accepts it, and accepted items are DRAFT documents.
CREATE TABLE ai_proposals (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL REFERENCES companies(id),
 kind VARCHAR(20) NOT NULL CHECK (kind IN ('BILL_EXTRACTION','SALES_EXTRACTION','JOURNAL_DRAFT')),
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

-- ===== Opt-in override of the maker-checker control =====
-- For companies where one person runs the books. Default FALSE keeps separation of duties enforced.
ALTER TABLE companies ADD COLUMN allow_self_approval BOOLEAN NOT NULL DEFAULT FALSE;

-- ===== Default administrator =====
-- Hashed with pgcrypto bcrypt ($2a$), which Spring's BCryptPasswordEncoder verifies. Change this password after first sign-in.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
INSERT INTO app_users(email, password_hash, display_name, is_admin)
VALUES ('imransuper787@gmail.com', crypt('Mega@2222222', gen_salt('bf', 12)), 'Administrator', TRUE)
ON CONFLICT (email) DO NOTHING;
INSERT INTO audit_events(company_id, actor, action, entity_type, entity_id, details)
VALUES (NULL, 'SYSTEM_BOOTSTRAP', 'DEFAULT_ADMIN_CREATED', 'USER', 'imransuper787@gmail.com', '{}'::jsonb);
