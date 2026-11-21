-- ===== Income-tax TCS (section 206C) collected on sales invoices =====
-- total_amount includes TCS (it is what the customer owes, and e-invoice TotInvVal carries it as OthChrg).
ALTER TABLE sales_invoices
  ADD COLUMN tcs_section VARCHAR(10),
  ADD COLUMN tcs_rate NUMERIC(5,3) CHECK (tcs_rate IS NULL OR (tcs_rate > 0 AND tcs_rate <= 20)),
  ADD COLUMN tcs_amount NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (tcs_amount >= 0),
  ADD CONSTRAINT chk_sales_invoices_tcs CHECK ((tcs_section IS NULL) = (tcs_rate IS NULL)
    AND (tcs_section IS NOT NULL OR tcs_amount = 0));
