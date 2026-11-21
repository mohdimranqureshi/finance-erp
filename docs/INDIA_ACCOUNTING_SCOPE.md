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
- GSTR-2B import and ITC reconciliation (B2B, B2BA amendments, CDNR credit/debit notes) against purchase bills and debit notes, with exception resolution, supplier follow-up email drafts, and bill proposals for invoices missing from the books. Report-only; nothing is posted automatically.
- Reverse charge on purchase bills (RCM liability and input credit as draft journals, matched against GSTR-2B reverse-charge lines) and income-tax TCS (section 206C) collected on sales invoices.
- GSTR-3B working sheet (tables 3.1, 3.2, 4 and 6.1) with ITC set-off in Rule 88A order and the cash needed for forward and reverse charge. A review aid, not a filing.
- TDS challans (deposit journals, allocation to deductions), the deductor TAN, and Form 26Q working data with due dates and indicative late-deposit interest.
- Opening balances (one draft opening journal per company from GL balances plus customer and vendor opening items, with the difference to Opening Balance Adjustment); opening items age in open items and are settled by receipts and payments like invoices and bills.
- Fixed assets with SLM/WDV book depreciation (Schedule II style, indicative lives), depreciation runs and disposals as draft journals.
- Tally XML import (preview, ledger and party mapping, idempotent draft journals per voucher) and export of posted journals as Tally Journal vouchers.
- When vision is enabled, PNG/JPEG and scanned-PDF bills are cleaned and re-encoded to PNG pages and read by the vision model, then go through the same server-side validation and review as text extractions; the originals are never stored.
- Cross-client workspace for accountants: close status per client and a bulk approval queue that uses the normal maker-checker rules.

## Not implemented

GST return filing and portal/GSP integration, IRN generation, e-way bill generation, GSTR-3B filing, exports/SEZ, nil-rated/exempt reporting, advances, blocked credits under section 17(5), GSTR-2B CDNRA/ISD/import (IMPG) reconciliation, debit notes against reverse-charge bills, section 206C(1H) and higher-rate (206CC/206AA) handling, TDS returns beyond 26Q working data (24Q, FVU file format, lower-deduction certificates), bank feeds, GST on the sale of fixed assets, asset revaluation and impairment, multi-currency, consolidation, milestone/usage-based revenue recognition, Ind AS/Schedule III statement formats, Tally opening balances and bill-wise import. Payroll and inventory/stock accounting are out of scope by design: this is a finance-only product, and such transactions enter as journal entries (stock and salary vouchers in Tally files are skipped or imported as plain journals). The items above are separate milestones requiring versioned rules, government API credentials where applicable, and review by qualified Indian accounting/tax professionals. GST, TDS and TCS rates in the code are reference data and must be verified. Do not treat generated AI content as accounting advice.

Financial values use PostgreSQL `NUMERIC(19,4)` and application checks limit inputs to four decimal places. A complete accounting product still needs a formally approved rounding policy, source-document model, tax-rule effective dates, audit/export formats, retention policy, and signed-off examples for each supported transaction type.
