# Production readiness and release gate

This repository is a pre-production release candidate. It is not a certified accounting system and must not be used for live books until every release gate below has an owner, evidence and sign-off.

## Implemented in this milestone

- Single executable Spring Boot JAR with the compiled React/Vite frontend in `static/`.
- PostgreSQL + Flyway schema migrations, including approval metadata and append-only database triggers for posted journals and audit events.
- Company membership and role checks in the API.
- Maker-checker journal workflow: draft → submitted → independently approved → posted.
- Reversals now create draft entries and must pass the same submit/approve/post workflow.
- Period overlap serialization and period-row locking when posting/approving, plus close-period guard against draft/submitted/approved journals.
- One-time bootstrap identity support using `BOOTSTRAP_ADMIN_*` environment variables; it does not overwrite an existing account.
- Company invitation flow with 256-bit random one-time token, SHA-256 token storage, 48-hour expiry, and password creation on acceptance; token delivery is manual over a trusted channel.
- CI workflow, database backup script and non-destructive restore-test script.
- Production profile requires explicit database credentials and disables public registration by default.

## Required before accepting real financial records

- [ ] Run `mvn clean verify` on a machine with JDK 17+, Maven 3.9+ and Docker; save the test report as release evidence.
- [ ] Complete a professional security review of HTTP Basic authentication, browser credential handling, brute-force protection, MFA, account recovery, session/logout policy and CSRF/CORS posture. Do not expose the app publicly until this is complete.
- [ ] Deploy behind HTTPS/TLS with trusted-proxy configuration, security headers, strict network controls and no public PostgreSQL port.
- [ ] Use separate least-privilege runtime and migration DB roles; the runtime role should not own the schema or have permission to disable triggers.
- [ ] Store DB/bootstrap secrets in a secret manager. Set `BOOTSTRAP_ADMIN_EMAIL` and a unique `BOOTSTRAP_ADMIN_PASSWORD` (16–72 characters) for the first startup only, then remove both from the environment and rotate any deployment secrets. Existing users are never reset by this bootstrap runner.
- [ ] Configure a trusted invitation-delivery channel; invitation tokens are currently returned once to the owner for manual secure delivery. Add MFA, rate limiting, account disable/revocation and security event alerting.
- [ ] Configure encrypted off-host backups, retention, monitoring, and scheduled restore drills. The included restore script only demonstrates restoring to a separate local test database.
- [ ] Add centralized tamper-resistant audit export/retention, observability, alerting, vulnerability/dependency/container scanning, SBOM and incident-response procedures.
- [ ] Have an Indian CA/accounting-domain expert validate the chart of accounts, GST, TDS/TCS, e-invoicing, e-way bill, credit/debit notes, bank reconciliation, inventory integration, rounding, FX, financial year close, audit exports and applicable statutory reporting. These features are not implemented by this foundation.
- [ ] Complete performance/load tests, concurrency tests, migration-upgrade tests, disaster recovery, access-control matrix tests and a penetration test.

## Local release verification

```cmd
cd /d C:\Projects\finance-erp\backend
mvn clean verify
```

This invokes the frontend build via `frontend-maven-plugin`, packages the UI in the JAR, and runs integration tests using Testcontainers (Docker must be running).

## Bootstrap a first production user

Set these only for the first startup against an empty database. Use PowerShell/CMD secret handling appropriate to your host; do not commit real values to `.env` or source control.

```cmd
set SPRING_PROFILES_ACTIVE=prod
set DB_URL=jdbc:postgresql://YOUR_PRIVATE_DB_HOST:5432/finance_erp
set DB_USERNAME=finance_erp_app
set DB_PASSWORD=YOUR_RUNTIME_SECRET
set FLYWAY_DB_USERNAME=finance_erp_migrator
set FLYWAY_DB_PASSWORD=YOUR_MIGRATION_SECRET
set BOOTSTRAP_ADMIN_EMAIL=admin@your-company.in
set BOOTSTRAP_ADMIN_PASSWORD=YOUR_UNIQUE_RANDOM_PASSWORD_AT_LEAST_16_CHARS
java -jar backend\target\finora-ai.jar
```

After successful first startup, remove `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD` from the runtime environment. The bootstrap runner only creates an identity; sign in and create the company through the normal UI so the owner membership and default Indian financial year are created together. Configure separate least-privilege runtime/migration database users before production. `DB_USERNAME` is the runtime account; `FLYWAY_DB_USERNAME` is used only for schema migrations.
