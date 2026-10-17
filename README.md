# FinoraAI — Milestone 3 (approval workflow + deployment hardening)

India-first finance-only ERP foundation using Java 17, Spring Boot, PostgreSQL, Flyway, React and TypeScript. **This is a hardened development/pre-production baseline, not yet a certified production accounting system.**

## Added in this milestone

- Maker-checker journal workflow: `DRAFT → SUBMITTED → APPROVED → POSTED`.
- The submitter cannot approve their own journal; the API checks this using authenticated user IDs.
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
- `POST /api/v1/companies/{companyId}/journals/{journalId}/approve` approves (owner/approver, not the submitter).
- `POST /api/v1/companies/{companyId}/journals/{journalId}/post` posts only an approved journal.
- `POST /api/v1/companies/{companyId}/journals/{journalId}/reverse` creates a linked equal-and-opposite posted reversal (owner/approver).

Migration V3 is applied automatically by Flyway. Do not delete a database volume to apply a migration.

## Release blockers before real production use

This is not a claim of production readiness. The following still require implementation/review: replace HTTP Basic with a production identity/session strategy (MFA, recovery, lockout/rate limiting), CSRF-safe browser auth, secure first-owner onboarding (public registration is currently open), invitation verification, secret rotation, backups and tested restore, deployment-specific TLS/proxy config, dependency/container vulnerability scans, structured monitoring/alerting, data retention/export, full automated authorization and concurrency tests, accounting-domain review, GST/TDS/e-invoicing and statutory validation. Audit logs currently live in the same database and are not tamper-evident against database administrators. Use synthetic data only until these are closed.

## Milestone 4: release hardening baseline

- Posted journals and their lines are immutable at the database-trigger level; use reversals instead. Audit events are append-only at the database-trigger level.
- Reversals are created as drafts and must go through submit → independent approval → post. They no longer affect reports immediately.
- Period creation serializes per company to reduce overlap races; posting/approval lock the open period, and closing refuses periods with any unposted journals.
- Production registration is disabled by default. Use the one-time `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD` environment variables for a new production database, then remove them. The bootstrap never overwrites an existing account.
- Owners can create one-time company invitations at `POST /api/v1/companies/{companyId}/invitations`. The response contains a high-entropy token shown once, valid for 48 hours. Deliver it manually over a trusted channel until a verified email provider is configured. The invited person accepts it at `POST /api/v1/auth/accept-invitation` and chooses their own password.
- CI workflow and local PostgreSQL backup/restore-test scripts are included under `.github/workflows/` and `scripts/`.

See `docs/PRODUCTION_READINESS.md` for the mandatory release gate and `docs/INDIA_ACCOUNTING_SCOPE.md` for accounting features that remain out of scope. These additions are not a claim that the app is ready for live financial records; local build, integration tests, security review and accounting-domain validation are still required.

## Local backup and restore drill (Windows CMD)

```cmd
scripts\backup-db.bat
scripts\restore-test-db.bat backups\finance_erp-YYYYMMDD-HHMMSS.dump
```

The restore script targets a separate `finance_erp_restore_test` database, not your active `finance_erp` database. For local custom passwords, set `POSTGRES_PASSWORD` in the same CMD session before running scripts.
