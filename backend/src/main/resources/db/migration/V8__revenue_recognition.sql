-- Revenue recognition schedules: invoice revenue is deferred, then recognised monthly through draft journals.
CREATE TABLE revenue_schedules (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL REFERENCES companies(id),
 sales_invoice_id BIGINT NOT NULL,
 description VARCHAR(300) NOT NULL,
 total_amount NUMERIC(19,4) NOT NULL CHECK (total_amount > 0),
 start_date DATE NOT NULL,
 months INTEGER NOT NULL CHECK (months BETWEEN 1 AND 120),
 revenue_account_id BIGINT NOT NULL,
 deferred_account_id BIGINT NOT NULL,
 deferral_journal_id BIGINT NOT NULL,
 created_by_user_id BIGINT NOT NULL REFERENCES app_users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(company_id, id),
 FOREIGN KEY(company_id, sales_invoice_id) REFERENCES sales_invoices(company_id, id),
 FOREIGN KEY(company_id, revenue_account_id) REFERENCES accounts(company_id, id),
 FOREIGN KEY(company_id, deferred_account_id) REFERENCES accounts(company_id, id),
 FOREIGN KEY(company_id, deferral_journal_id) REFERENCES journal_entries(company_id, id)
);
CREATE INDEX idx_revenue_schedules_invoice ON revenue_schedules(company_id, sales_invoice_id);

CREATE TABLE revenue_schedule_entries (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL,
 schedule_id BIGINT NOT NULL,
 recognition_date DATE NOT NULL,
 amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
 journal_entry_id BIGINT,
 UNIQUE(company_id, id),
 UNIQUE(company_id, schedule_id, recognition_date),
 FOREIGN KEY(company_id, schedule_id) REFERENCES revenue_schedules(company_id, id),
 FOREIGN KEY(company_id, journal_entry_id) REFERENCES journal_entries(company_id, id)
);
CREATE INDEX idx_revenue_entries_due ON revenue_schedule_entries(company_id, recognition_date) WHERE journal_entry_id IS NULL;
