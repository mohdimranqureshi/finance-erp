# India accounting scope and current boundaries

The current implementation is a configurable double-entry ledger foundation with INR as the default currency, an April–March financial year, company-specific accounts, accounting periods, trial balance, audit events, and maker-checker journals.

It does **not** yet calculate or file GST/TDS/TCS, generate GST returns, e-invoices or e-way bills, reconcile bank feeds, generate statutory financial statements, handle inventory/valuation, multi-currency revaluation, depreciation, payroll, consolidation, or automate statutory compliance. Those are separate product milestones requiring versioned rules and review by qualified Indian accounting/tax professionals. Do not treat generated AI content as accounting advice or allow AI to approve/post entries.

Financial values use PostgreSQL `NUMERIC(19,4)` and application checks limit inputs to four decimal places. A complete accounting product still needs a formally approved rounding policy, source-document model, tax-rule effective dates, audit/export formats, retention policy, and signed-off examples for each supported transaction type.
