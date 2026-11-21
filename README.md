# FinoraAI — Milestone 3 (approval workflow + deployment hardening)

India-first finance-only ERP foundation using Java 17, Spring Boot, PostgreSQL, Flyway, React and TypeScript. **This is a hardened development/pre-production baseline, not yet a certified production accounting system.**

**Scope:** this product is deliberately finance-only. Payroll and inventory/stock management are out of scope by design, not pending work. Salary and stock entries from other systems come in as ordinary journal entries (for example through the Tally import), and the product's value is in accounting, GST/TDS/TCS compliance, reconciliation and the close.

## Added in this milestone

- Maker-checker journal workflow: `DRAFT → SUBMITTED → APPROVED → POSTED`.
- The submitter cannot approve their own journal; the API checks this using authenticated user IDs. An owner can override this per company at `PUT /api/v1/companies/{id}/settings` (`allowSelfApproval`), which is off by default; self-approvals are then recorded as `JOURNAL_SELF_APPROVED`.
- Approval and posting are recorded with actor IDs and timestamps, plus audit events.
- Posting requires approval and an open accounting period; balance and minimum-line checks are repeated server-side.
- Production profile requires explicit database environment variables (no embedded production DB credentials).
- Safer error responses, conservative security headers/CSP, health endpoint details hidden in production.
- Single-JAR build still packages React frontend into Spring Boot.

## Build a single JAR

Prerequisites: JDK 17+, Maven 3.9+, internet access for first-time dependency downloads. From the project root:

```cmd
cd backend
mvn clean package
```

The executable should be `backend\target\finora-ai.jar`. Tests use Testcontainers and require Docker. If you need to build without tests temporarily, use `mvn clean package -DskipTests`, but do not treat that as release verification.

## Local run

From the project root, set a local database password consistently in `.env` and `docker-compose.yml` environment (or export `DB_PASSWORD` for the Java process). Start PostgreSQL:

```cmd
docker compose up -d postgres
```

Run the JAR with local profile defaults (development only):

```cmd
java -jar backend\target\finora-ai.jar
```

Open `http://localhost:8080`. PostgreSQL remains a separate service; only frontend + backend are in the JAR.

## Production profile configuration

Use a strong secret manager or environment variables, a managed/private PostgreSQL database, TLS reverse proxy, backups, monitoring, and a least-privilege database user. Set these before starting the JAR:

```cmd
set SPRING_PROFILES_ACTIVE=prod
set PORT=8080
set DB_URL=jdbc:postgresql://YOUR_PRIVATE_DB_HOST:5432/finance_erp
set DB_USERNAME=finance_erp_app
set DB_PASSWORD=REPLACE_WITH_A_LONG_RANDOM_SECRET
java -jar backend\target\finora-ai.jar
```

Do not literally use the placeholder host/password. Do not expose PostgreSQL publicly. Configure TLS at a trusted reverse proxy/load balancer and allow only that proxy to reach the app. Rotate secrets and validate backups/restore before accepting real records.

## Journal workflow API

- `POST /api/v1/companies/{companyId}/journals` creates a draft.
- `POST /api/v1/companies/{companyId}/journals/{journalId}/submit` submits (owner/accountant).
- `POST /api/v1/companies/{companyId}/journals/{journalId}/approve` approves (owner/approver, not the submitter unless the company has enabled self-approval).
- `POST /api/v1/companies/{companyId}/journals/{journalId}/post` posts only an approved journal.
- `POST /api/v1/companies/{companyId}/journals/{journalId}/reverse` creates a linked equal-and-opposite posted reversal (owner/approver).

Migration V3 is applied automatically by Flyway. Do not delete a database volume to apply a migration.

## Release blockers before real production use

This is not a claim of production readiness. Browser sign-in now uses server sessions (HttpOnly, SameSite=Strict cookies, 30-minute idle and 12-hour absolute timeouts), CSRF tokens, per-email lockout, optional TOTP MFA with single-use recovery codes, and admin account disable and MFA reset. HTTP Basic is switched off in the `prod` profile and refused for MFA-enabled accounts. Sessions are held in memory, so run a single instance until Spring Session is added. The following still require implementation/review: a professional security review of that auth, mandatory MFA for administrators, IP-based rate limiting, secure first-owner onboarding (public registration is currently open), invitation verification, secret rotation, backups and tested restore, deployment-specific TLS/proxy config, dependency/container vulnerability scans, structured monitoring/alerting, data retention/export, full automated authorization and concurrency tests, accounting-domain review, GST/TDS/e-invoicing and statutory validation. Audit logs currently live in the same database and are not tamper-evident against database administrators. Use synthetic data only until these are closed.

## Milestone 4: release hardening baseline

- Posted journals and their lines are immutable at the database-trigger level; use reversals instead. Audit events are append-only at the database-trigger level.
- Reversals are created as drafts and must go through submit → independent approval → post. They no longer affect reports immediately.
- Period creation serializes per company to reduce overlap races; posting/approval lock the open period, and closing refuses periods with any unposted journals.
- Production registration is disabled by default. Use the one-time `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD` environment variables for a new production database, then remove them. The bootstrap never overwrites an existing account.
- Owners can create one-time company invitations at `POST /api/v1/companies/{companyId}/invitations`. The response contains a high-entropy token shown once, valid for 48 hours. Deliver it manually over a trusted channel until a verified email provider is configured. The invited person accepts it at `POST /api/v1/auth/accept-invitation` and chooses their own password.
- CI workflow and local PostgreSQL backup/restore-test scripts are included under `.github/workflows/` and `scripts/`.

See `docs/PRODUCTION_READINESS.md` for the mandatory release gate and `docs/INDIA_ACCOUNTING_SCOPE.md` for accounting features that remain out of scope. These additions are not a claim that the app is ready for live financial records; local build, integration tests, security review and accounting-domain validation are still required.

## Milestone 5: India GST sub-ledgers (Flyway V6)

Workflow: set the company GST profile (state, GSTIN, PAN) → set up the Indian chart of accounts → add customers/vendors → create GST sales invoices and purchase bills → **Issue**/**Book** creates a draft journal → a different user approves → post. Reports: GST summary, P&L, balance sheet.

- `PUT /api/v1/companies/{id}/india/profile`, `POST .../india/chart-of-accounts`, `GET /api/v1/india/reference`
- `POST|GET .../parties`, `.../sales-invoices` (+ `/{id}/issue`), `.../purchase-bills` (+ `/{id}/book`)
- `GET .../reports/gst-summary|profit-and-loss|balance-sheet`
- `POST .../ai/suggest-bill-coding` (keyword rules only; no LLM configured)

Not yet built: GST return filing, e-invoicing, e-way bills, RCM, credit notes, payments and bank reconciliation. See `docs/INDIA_ACCOUNTING_SCOPE.md`. GST/TDS rates are reference data to be verified by a qualified professional.

## Milestone 6: settlements, reconciliation, notes, compliance data (Flyway V7)

- `POST .../sales-invoices/{id}/receipts`, `POST .../purchase-bills/{id}/payments`, `GET .../payments`, `GET .../open-items` (ageing)
- `POST .../sales-invoices/{id}/credit-notes`, `POST .../purchase-bills/{id}/debit-notes`, `GET .../adjustment-notes`
- `POST|GET .../bank/statement-lines`, `POST .../bank/statement-lines/{id}/match|unmatch`, `GET .../bank/reconciliation`
- `GET .../gst/gstr1`, `GET .../sales-invoices/{id}/e-invoice-payload`, `POST .../sales-invoices/{id}/e-way-bill-payload`, `POST .../sales-invoices/{id}/irn`
- `POST .../year-end/closing-journal`
- `POST .../sales-invoices/{id}/revenue-schedule`, `POST .../revenue-schedules/recognize`, `GET .../revenue-schedules` (Flyway V8): defers invoice revenue to Deferred Revenue (re-run chart setup to add account 2300), then drafts monthly recognition journals. Straight-line only; a full month is recognised from the start month.

Payloads are generated for review and are never transmitted to GST portals; IRN and e-way bill numbers come from the portal/GSP. Every ledger effect is a draft journal that needs independent approval. Not built: portal/GSP integration, GSTR-3B filing, multi-currency, consolidation. Payroll and inventory are out of scope by design.

## Milestone 7: AI layer (Flyway V9)

The LLM proposes; people approve. Output is validated on the server (GSTIN checksum, HSN/SAC, GST slabs by date, duplicate bills, totals, chart-of-accounts membership, balanced journals), stored as a proposal, and only becomes a DRAFT document when a person with the Owner or Accountant role accepts it. Drafts still go through independent maker-checker approval, so AI can never approve or post.

- `POST .../ai/extract-bill` (pasted invoice text to a draft purchase bill proposal), `POST .../ai/draft-journal` (instruction to a journal proposal)
- `GET .../ai/proposals`, `POST .../ai/proposals/{id}/accept|reject`
- `POST .../ai/ask` answers only from the company's P&L, balance sheet, GST summary and open items; `figuresUsed` entries carry a `source` (account, invoice or bill) that the UI drills into
- `GET .../ai/anomalies`, `GET .../ai/flux?from&to`, `GET .../ai/close-checklist[?periodId]`, `GET .../ai/metrics`: figures come from SQL and the report services; with `narrative=true` the AI only phrases them
- `POST .../ai/categorise-bank-lines` (draft-journal proposals for unexplained statement lines, Flyway V12), `POST .../ai/reconcile-suggest` (exact-amount match candidates, AI breaks ties among them only)
- `POST .../ai/extract-bill-file` (multipart `file`: PDF with selectable text, TXT or CSV, up to 1 MB). Images and scanned PDFs are refused: the model is text-only and no OCR is bundled
- `GET .../ai/review[?narrative=true]` runs deterministic close checks (stuck journals, duplicate bills, unmatched bank lines, overdue receivables, open old periods); the AI only phrases the summary

Any OpenAI-compatible chat-completions endpoint works, including a local one. It is off by default:

```cmd
set AI_ENABLED=true
set AI_BASE_URL=https://api.openai.com/v1
set AI_API_KEY=your-key
set AI_MODEL=gpt-4o-mini
```

For the local profile use the properties `app.ai.enabled`, `app.ai.base-url`, `app.ai.api-key`, `app.ai.model` (or `APP_AI_*` environment variables). `https` is required except for localhost. Invoice text and company figures are sent to the provider you configure, so choose one that is acceptable for your data-protection obligations (including the DPDP Act). Only a hash and length of source text are stored. Keep the API key in a secret manager.

## Local backup and restore drill (Windows CMD)

```cmd
scripts\backup-db.bat
scripts\restore-test-db.bat backups\finance_erp-YYYYMMDD-HHMMSS.dump
```

The restore script targets a separate `finance_erp_restore_test` database, not your active `finance_erp` database. For local custom passwords, set `POSTGRES_PASSWORD` in the same CMD session before running scripts.

## Milestone 8: GSTR-2B ITC reconciliation (Flyway V2)

- `POST .../gstr2b/imports` accepts the GSTR-2B JSON downloaded from the GST portal. It reads `b2b`, `b2ba` (amendments) and `cdnr` (credit and debit notes); other sections are counted as skipped. Also: `GET .../gstr2b/imports[/{id}]`, `GET .../gstr2b/imports/{id}/items[?status=]`, `POST .../gstr2b/imports/{id}/reconcile`, `POST .../gstr2b/items/{id}/resolve` (`ACCEPTED`, `FOLLOW_UP_VENDOR` or `IGNORED`) and `DELETE .../gstr2b/imports/{id}` (owner only).
- Invoices and supplier debit notes match purchase bills; supplier credit notes match purchase debit notes. Matching uses supplier GSTIN and a normalised document number, with a ₹1 per-tax-head tolerance. Amendments also try the original number.
- The summary reports `itcMatched`, `itcAtRisk`, `itcNotRecordedInBooks` and `itcToReverse`. Resolutions survive a re-run, but item ids change, so reload the items after re-running.
- `GET .../gstr2b/imports/{id}/vendor-follow-ups` lists suppliers to chase. `POST .../vendor-follow-ups/{vendorId}/email[?ai=true]` drafts an email but does not send it. The server always builds the document table; with AI on, the model only writes the greeting and closing, never receives amounts, and its text is discarded if it contains figures.
- `POST .../gstr2b/items/{id}/draft-bill` turns a portal invoice missing from the books into a bill proposal without using an LLM. It copies HSN/SAC and the expense account from the vendor's last bill; if there is none, pass `hsnSac` to `.../ai/proposals/{id}/accept`. The bill then follows the usual draft → independent approval → post flow.
- Reconciliation is report-only and never posts to the ledger.

## Milestone 9: reverse charge, TCS, GSTR-3B and TDS (Flyway V3, V4, V5)

Migration numbers are not contiguous: V8 is intentionally unused.

- **Reverse charge (RCM):** `POST .../purchase-bills` accepts `reverseCharge: true`. The supplier invoice carries no GST, so the bill total (the amount payable to the supplier) equals the taxable value, and the GST is posted as an RCM liability (accounts 2130/2140/2150) plus input credit when ITC is eligible. RCM bills match RCM lines in GSTR-2B. Debit notes against RCM bills are refused for now.
- **TCS (section 206C):** `POST .../sales-invoices` accepts `tcsSection` and `tcsRate`. TCS is computed on the GST-inclusive value in whole rupees, added to the invoice total, and posted to *TCS Payable* (2210). Codes are listed under `tcsSections` in `GET /api/v1/india/reference` and are indicative only.
- **GSTR-3B working sheet:** `GET .../gst/gstr3b?period=MMYYYY[&itcBasis=books|gstr2b]` returns tables 3.1, 3.2, 4 and 6.1 for a month, including ITC set-off in Rule 88A order and the cash needed for forward and reverse charge. With `itcBasis=gstr2b` it uses the ITC reported in that month's imported GSTR-2B. It is a review aid, not a filing.
- **TDS deposits and 26Q:** set the company `tan` in `PUT .../india/profile`. `POST .../tds/challans` records a deposit as a draft journal and can allocate it to deductions; `GET .../tds/deductions`, `GET .../tds/challans`, `POST .../tds/challans/{id}/allocations`. `GET .../tds/26q?fy=2026-27&quarter=1` returns Form 26Q working data with due dates, indicative late-deposit interest and a list of problems (missing TAN or PAN, unallocated TDS). It is not the NSDL file format.

## Opening balances (Flyway V6)

`POST .../opening-balances` records the opening trial balance as of a date in one draft journal (maker-checker applies): GL lines as entered, customer receivables totalled to Trade Receivables, vendor payables totalled to Trade Payables, and any difference to *3900 Opening Balance Adjustment*. GL lines may not hit AR/AP directly, so the party ledger always agrees with the GL. Party items appear in `GET .../open-items` (`documentType: OPENING_BALANCE`, `openingItemId`) and are settled with `POST .../opening-balances/items/{itemId}/receipts` (TDS deducted by the customer allowed) or `.../payments`. One batch per company; to replace it, post, reverse and post the reversal of its journal first. `GET .../opening-balances` shows the batch and the outstanding party items.

## Fixed assets and depreciation (Flyway V7)

- `GET /api/v1/fixed-assets/categories` lists indicative Schedule II useful lives (reference data for a CA to confirm).
- `POST|GET .../fixed-assets` creates and lists assets (SLM or WDV, residual value defaulting to 5%, optional capitalisation journal from the expense account a bill used, optional opening accumulated depreciation for brought-forward assets). `GET .../fixed-assets/{id}` includes the depreciation history.
- `POST .../fixed-assets/depreciation-runs {periodEnd}` creates one draft journal for all active assets, pro-rata by days and never below residual value; runs cannot overlap. `POST .../fixed-assets/{id}/dispose` creates a draft disposal journal including any stub-period depreciation and the gain or loss. GST on the sale of an asset is not handled: raise a sales invoice if output tax is due.
- A run updates each asset's accumulated depreciation when the draft is created. If that draft is later rejected, the asset and ledger disagree until it is corrected.

## Tally XML import and export

- `POST .../tally/import/preview` (multipart `file`, optional `mapping` JSON) shows how each Tally ledger maps to an account or party, the vouchers per type and date range, unbalanced or unmapped vouchers, and opening balances (reported only).
- `POST .../tally/import` creates missing accounts and parties, then one **draft** journal per balanced voucher, to be approved through the normal workflow. Re-importing the same file skips vouchers already imported (keyed by Tally GUID). `mapping` overrides look like `{"Ledger name": accountId | "EXPENSE" | "CUSTOMER" | "VENDOR"}`.
- `GET .../tally/export?from=YYYY-MM-DD&to=YYYY-MM-DD` downloads posted journals as a Tally "Import Data" XML with ledger masters and Journal vouchers.
- Files up to 10 MB in UTF-16 (Tally's default) or UTF-8 are accepted; DTDs and external entities are rejected. Sundry Debtors and Creditors become customers and vendors posting to Trade Receivables and Payables. Opening balances, inventory and bill-wise details are not imported. This also raises the general upload size limit to about 12 MB; the AI file upload still enforces its own limit. See `samples/tally-daybook-sample.xml`.

## Image and scanned-PDF extraction (optional)

Set `AI_VISION_ENABLED=true` (and `AI_VISION_MODEL` if your text model cannot read images) to accept PNG/JPEG bills and invoices and PDFs without selectable text. The server checks the file's real content, refuses oversized images, removes all metadata by re-encoding to PNG (at most 2000 px), and renders up to `AI_MAX_IMAGE_PAGES` (default 3) PDF pages; files may be up to `AI_MAX_IMAGE_BYTES` (default 5MB). These page images, not the original file, are sent to your provider. The result goes through the same GSTIN, HSN/SAC, GST rate, duplicate and total checks as text, is marked as read from an image, and still needs a person to accept it and a different person to approve it. Only a hash and the size of the file are stored. If your default profile still uses Spring's 1 MB upload limit, raise `spring.servlet.multipart.max-file-size` and `max-request-size` (the `prod` profile already does).

## CA multi-client workspace (Flyway V9)

- `GET /api/v1/workspace/clients[?search&status&sort]` lists every company you are a member of with draft, submitted and approvable journals, unmatched bank lines, the latest GSTR-2B import, overdue receivables and open old periods, plus a `closeStatus` (`CLEAN`, `ATTENTION` or `BLOCKED`) with reasons. `PUT .../workspace/clients/{companyId}/settings` pins a client or adds a label.
- `GET /api/v1/workspace/approvals` is the cross-client queue of submitted journals you may approve. `POST /api/v1/workspace/approvals/bulk` approves up to 100 through the same rules and audit events as the per-company screen, each in its own transaction, and never posts. The web app has a **Clients** page for both.

## Session sign-in, CSRF and MFA (Flyway V10)

- Browsers sign in with `POST /api/v1/auth/login` (`{email, password, totpCode?, recoveryCode?}`), which returns `401 {"mfaRequired":true}` when a second factor is needed, and `POST /api/v1/auth/logout`. `GET /api/v1/auth/csrf` issues the `XSRF-TOKEN` cookie, echoed in `X-XSRF-TOKEN`. CSRF is required for unsafe requests that carry a session cookie, and always for login and logout; Basic-header requests without a session cookie are exempt.
- Users enrol with `POST /api/v1/auth/mfa/setup` and `/mfa/enable` (which returns 10 single-use recovery codes once) and remove it with `/mfa/disable`. Admins can `POST /api/v1/admin/users/{id}/disable|enable` and `/{id}/mfa/reset`; these end that user's sessions.
- TOTP secrets are encrypted with AES-256-GCM. **In the `prod` profile `MFA_ENCRYPTION_KEY` (base64 of 32 random bytes, e.g. `openssl rand -base64 32`) is required and the app will not start without it.** Outside prod an ephemeral key is generated with a warning, and enrolled MFA stops working after a restart. `app.security.basic-auth-enabled` defaults to `true` and is `false` in `prod`.
- Remaining risks: sessions and lockout are in memory (single instance); there is no per-IP rate limiting; MFA enrolment does not re-ask for the password.
