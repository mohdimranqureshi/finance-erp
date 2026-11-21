-- ===== Reverse charge (RCM) on purchase bills =====
-- For RCM bills the GST columns hold the tax the recipient pays to the government, and total_amount is the
-- amount payable to the supplier (taxable value only), so settlements and AP stay correct.
ALTER TABLE purchase_bills ADD COLUMN reverse_charge BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE purchase_bills ADD CONSTRAINT chk_purchase_bills_rcm_total
  CHECK (NOT reverse_charge OR total_amount = taxable_amount);
