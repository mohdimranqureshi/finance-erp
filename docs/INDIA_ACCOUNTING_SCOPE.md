# India accounting scope and current boundaries

The application is a double-entry ledger with INR as the default currency, an April–March financial year, company-specific accounts, accounting periods, trial balance, audit events, and maker-checker journals.

## Implemented (needs CA validation before live use)

- Company GST profile: GSTIN (format and checksum validated), PAN, state code.
- Indian chart-of-accounts starter with GST input/output, TDS payable and receivable control accounts.
- Customers and vendors with GSTIN/state validation.
- GST sales invoices: CGST+SGST vs IGST by place of supply, effective-dated GST slabs (12%/28% before 22 Sep 2025; 40% from that date), HSN/SAC format checks, gapless per-financial-year numbering (`INV/26-27/00001`).
- Purchase bills: input tax credit toggle (ineligible GST is costed), TDS section/rate with whole-rupee rounding, duplicate vendor-invoice detection.
- Issuing an invoice or booking a bill creates a DRAFT journal only; it must pass independent approval before posting.
- Reports: GST summary, profit and loss, balance sheet (posted journals only).
- AI layer: LLM bill extraction and journal drafting as validated proposals, grounded Q&A, and an automated close review. No LLM is configured by default, and AI cannot approve or post.
- Receipts (with TDS deducted by the customer) and vendor payments against invoices/bills, open-item ageing.
- Sales credit notes and purchase debit notes, netted in the GST summary.
- Bank statement import (paste rows), match suggestions on amount and date, and a reconciliation summary.
- GSTR-1 working data (b2b, b2cl, b2cs, cdnr, cdnur, hsn); e-invoice (IRP v1.1) and e-way bill JSON payloads; manual IRN recording. Nothing is transmitted to GST portals or a GSP.
- Year-end closing journal (draft, maker-checker) that moves profit or loss to reserves; closing entries are excluded from the P&L.
- Revenue recognition schedules: straight-line monthly deferral and recognition via draft journals (no milestone, usage-based or Ind AS 115 multi-element allocation).

## Not implemented

GST return filing and portal/GSP integration, IRN generation, e-way bill generation, GSTR-3B filing, reverse charge, exports/SEZ, nil-rated/exempt reporting, advances, TCS, ITC reconciliation with GSTR-2B and blocked credits, bank feeds and opening-balance tracking, TDS challans and returns (24Q/26Q), payroll, inventory, fixed assets and depreciation, multi-currency, consolidation, milestone/usage-based revenue recognition, Ind AS/Schedule III statement formats. These are separate milestones requiring versioned rules, government API credentials where applicable, and review by qualified Indian accounting/tax professionals. GST and TDS rates in the code are reference data and must be verified. Do not treat generated AI content as accounting advice.

Financial values use PostgreSQL `NUMERIC(19,4)` and application checks limit inputs to four decimal places. A complete accounting product still needs a formally approved rounding policy, source-document model, tax-rule effective dates, audit/export formats, retention policy, and signed-off examples for each supported transaction type.
