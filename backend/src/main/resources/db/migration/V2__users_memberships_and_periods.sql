CREATE TABLE app_users (
 id BIGSERIAL PRIMARY KEY,
 email VARCHAR(254) NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL,
 display_name VARCHAR(180) NOT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
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
