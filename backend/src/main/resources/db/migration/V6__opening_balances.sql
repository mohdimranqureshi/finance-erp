-- ===== Opening balances =====
-- One opening batch per company creates a single DRAFT journal (maker-checker applies). Party items are the
-- receivable/payable sub-ledger carried forward and are settled like invoices/bills.
CREATE TABLE opening_balance_batches (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL REFERENCES companies(id),
 as_of_date DATE NOT NULL,
 -- ACTIVE until its journal is reversed (reversal posted) and a replacement batch is entered.
 status VARCHAR(12) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUPERSEDED')),
 receivable_total NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (receivable_total >= 0),
 payable_total NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (payable_total >= 0),
 -- Signed: positive is a credit to the OPENING_BALANCE equity account, negative a debit.
 equity_adjustment NUMERIC(19,4) NOT NULL DEFAULT 0,
 journal_entry_id BIGINT NOT NULL,
 created_by_user_id BIGINT NOT NULL REFERENCES app_users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 superseded_at TIMESTAMPTZ,
 UNIQUE(company_id, id),
 UNIQUE(company_id, journal_entry_id),
 CHECK ((status = 'SUPERSEDED') = (superseded_at IS NOT NULL)),
 FOREIGN KEY(company_id, journal_entry_id) REFERENCES journal_entries(company_id, id)
);
CREATE UNIQUE INDEX uq_opening_batch_active ON opening_balance_batches(company_id) WHERE status = 'ACTIVE';

CREATE TABLE opening_balance_items (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL,
 batch_id BIGINT NOT NULL,
 item_type VARCHAR(10) NOT NULL CHECK (item_type IN ('RECEIVABLE','PAYABLE')),
 party_id BIGINT NOT NULL,
 document_number VARCHAR(50) NOT NULL CHECK (btrim(document_number) <> ''),
 document_date DATE NOT NULL,
 due_date DATE,
 amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(company_id, id),
 CHECK (due_date IS NULL OR due_date >= document_date),
 FOREIGN KEY(company_id, batch_id) REFERENCES opening_balance_batches(company_id, id),
 FOREIGN KEY(company_id, party_id) REFERENCES parties(company_id, id)
);
CREATE UNIQUE INDEX uq_opening_item_document ON opening_balance_items(company_id, batch_id, item_type, party_id, lower(document_number));
CREATE INDEX idx_opening_items_batch ON opening_balance_items(company_id, batch_id);

-- Receipts/payments may settle an opening item instead of an invoice/bill.
ALTER TABLE payments ADD COLUMN opening_item_id BIGINT;
ALTER TABLE payments ADD CONSTRAINT fk_payments_opening_item
  FOREIGN KEY(company_id, opening_item_id) REFERENCES opening_balance_items(company_id, id);
CREATE INDEX idx_payments_opening_item ON payments(company_id, opening_item_id) WHERE opening_item_id IS NOT NULL;

-- Replace the unnamed V1 document check (default name payments_check) with one that also allows opening items.
DO $$
DECLARE c record;
BEGIN
  FOR c IN SELECT conname FROM pg_constraint
           WHERE conrelid = 'payments'::regclass AND contype = 'c'
             AND pg_get_constraintdef(oid) LIKE '%sales_invoice_id%' AND pg_get_constraintdef(oid) LIKE '%purchase_bill_id%'
  LOOP
    EXECUTE format('ALTER TABLE payments DROP CONSTRAINT %I', c.conname);
  END LOOP;
END $$;
ALTER TABLE payments ADD CONSTRAINT payments_document_check CHECK (
  (direction = 'RECEIPT' AND purchase_bill_id IS NULL AND num_nonnulls(sales_invoice_id, opening_item_id) = 1)
  OR (direction = 'PAYMENT' AND sales_invoice_id IS NULL AND num_nonnulls(purchase_bill_id, opening_item_id) = 1 AND tds_amount = 0));
