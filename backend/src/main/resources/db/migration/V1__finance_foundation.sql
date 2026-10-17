CREATE TABLE companies (
 id BIGSERIAL PRIMARY KEY,
 name VARCHAR(180) NOT NULL,
 legal_name VARCHAR(240),
 base_currency CHAR(3) NOT NULL DEFAULT 'INR' CHECK (base_currency ~ '^[A-Z]{3}$'),
 status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE accounts (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL REFERENCES companies(id),
 code VARCHAR(30) NOT NULL,
 name VARCHAR(180) NOT NULL,
 account_type VARCHAR(20) NOT NULL CHECK (account_type IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE')),
 parent_account_id BIGINT,
 active BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(company_id, code), UNIQUE(company_id,id),
 FOREIGN KEY(company_id,parent_account_id) REFERENCES accounts(company_id,id)
);
CREATE TABLE journal_entries (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL REFERENCES companies(id),
 journal_date DATE NOT NULL,
 description VARCHAR(500) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','POSTED','REVERSED')),
 idempotency_key VARCHAR(100),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), posted_at TIMESTAMPTZ,
 UNIQUE(company_id,id), UNIQUE(company_id,idempotency_key)
);
CREATE INDEX idx_journal_company_date ON journal_entries(company_id,journal_date);
CREATE TABLE journal_lines (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL,
 journal_entry_id BIGINT NOT NULL,
 account_id BIGINT NOT NULL,
 description VARCHAR(500),
 debit NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (debit >= 0),
 credit NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (credit >= 0),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(company_id,journal_entry_id) REFERENCES journal_entries(company_id,id),
 FOREIGN KEY(company_id,account_id) REFERENCES accounts(company_id,id),
 CHECK ((debit > 0 AND credit = 0) OR (credit > 0 AND debit = 0))
);
CREATE INDEX idx_journal_lines_entry ON journal_lines(company_id,journal_entry_id);
CREATE INDEX idx_journal_lines_account ON journal_lines(company_id,account_id);
CREATE TABLE audit_events (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT REFERENCES companies(id),
 actor VARCHAR(180) NOT NULL DEFAULT 'LOCAL_DEV',
 action VARCHAR(80) NOT NULL,
 entity_type VARCHAR(80) NOT NULL,
 entity_id VARCHAR(100),
 details JSONB NOT NULL DEFAULT '{}'::jsonb,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
