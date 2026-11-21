-- ===== Fixed assets and book depreciation (Companies Act 2013 Schedule II style) =====
-- Depreciation runs and disposals only create DRAFT journals; posting stays in the maker-checker workflow.
CREATE TABLE fixed_assets (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL REFERENCES companies(id),
 asset_code VARCHAR(30),
 name VARCHAR(200) NOT NULL,
 category VARCHAR(40) NOT NULL,
 cost NUMERIC(19,4) NOT NULL CHECK (cost > 0),
 residual_value NUMERIC(19,4) NOT NULL CHECK (residual_value >= 0),
 put_to_use_date DATE NOT NULL,
 useful_life_years INTEGER NOT NULL CHECK (useful_life_years BETWEEN 1 AND 100),
 method VARCHAR(3) NOT NULL CHECK (method IN ('SLM','WDV')),
 -- Annual WDV rate as a fraction (0.631597 = 63.1597%); NULL for SLM.
 wdv_rate NUMERIC(9,6) CHECK (wdv_rate IS NULL OR (wdv_rate > 0 AND wdv_rate < 1)),
 asset_account_id BIGINT NOT NULL,
 capitalise_from_account_id BIGINT,
 capitalisation_journal_id BIGINT,
 source_bill_id BIGINT,
 -- Brought-forward assets: depreciation already charged up to opening_depreciated_to (ledger via opening balances).
 opening_accumulated_depreciation NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (opening_accumulated_depreciation >= 0),
 opening_depreciated_to DATE,
 accumulated_depreciation NUMERIC(19,4) NOT NULL DEFAULT 0,
 depreciated_to DATE,
 status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISPOSED')),
 disposal_date DATE,
 disposal_proceeds NUMERIC(19,4) CHECK (disposal_proceeds IS NULL OR disposal_proceeds >= 0),
 proceeds_account_id BIGINT,
 disposal_journal_id BIGINT,
 -- Positive is a gain, negative a loss.
 disposal_gain_loss NUMERIC(19,4),
 created_by_user_id BIGINT NOT NULL REFERENCES app_users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 disposed_at TIMESTAMPTZ,
 UNIQUE(company_id, id),
 CHECK (residual_value <= cost),
 CHECK ((method = 'WDV') = (wdv_rate IS NOT NULL)),
 CHECK (accumulated_depreciation >= opening_accumulated_depreciation AND accumulated_depreciation <= cost - residual_value),
 CHECK (opening_accumulated_depreciation = 0 OR opening_depreciated_to IS NOT NULL),
 CHECK (opening_depreciated_to IS NULL OR opening_depreciated_to >= put_to_use_date - 1),
 CHECK ((capitalise_from_account_id IS NULL) = (capitalisation_journal_id IS NULL)),
 CHECK (depreciated_to IS NULL OR depreciated_to >= put_to_use_date - 1),
 CHECK ((status = 'DISPOSED') = (disposal_date IS NOT NULL AND disposal_journal_id IS NOT NULL AND disposal_proceeds IS NOT NULL)),
 CHECK (disposal_date IS NULL OR disposal_date >= put_to_use_date),
 FOREIGN KEY(company_id, asset_account_id) REFERENCES accounts(company_id, id),
 FOREIGN KEY(company_id, capitalise_from_account_id) REFERENCES accounts(company_id, id),
 FOREIGN KEY(company_id, capitalisation_journal_id) REFERENCES journal_entries(company_id, id),
 FOREIGN KEY(company_id, source_bill_id) REFERENCES purchase_bills(company_id, id),
 FOREIGN KEY(company_id, proceeds_account_id) REFERENCES accounts(company_id, id),
 FOREIGN KEY(company_id, disposal_journal_id) REFERENCES journal_entries(company_id, id)
);
CREATE UNIQUE INDEX uq_fixed_asset_code ON fixed_assets(company_id, lower(asset_code)) WHERE asset_code IS NOT NULL;
CREATE INDEX idx_fixed_assets_status ON fixed_assets(company_id, status);
CREATE INDEX idx_fixed_assets_bill ON fixed_assets(company_id, source_bill_id) WHERE source_bill_id IS NOT NULL;

CREATE TABLE depreciation_runs (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL REFERENCES companies(id),
 period_end DATE NOT NULL,
 total_amount NUMERIC(19,4) NOT NULL CHECK (total_amount > 0),
 asset_count INTEGER NOT NULL CHECK (asset_count > 0),
 journal_entry_id BIGINT NOT NULL,
 created_by_user_id BIGINT NOT NULL REFERENCES app_users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(company_id, id),
 UNIQUE(company_id, period_end),
 FOREIGN KEY(company_id, journal_entry_id) REFERENCES journal_entries(company_id, id)
);

-- Depreciation history per asset: lines of a run, or the stub-period charge included in a disposal journal.
CREATE TABLE fixed_asset_depreciation (
 id BIGSERIAL PRIMARY KEY,
 company_id BIGINT NOT NULL,
 asset_id BIGINT NOT NULL,
 source VARCHAR(10) NOT NULL CHECK (source IN ('RUN','DISPOSAL')),
 run_id BIGINT,
 journal_entry_id BIGINT NOT NULL,
 from_date DATE NOT NULL,
 to_date DATE NOT NULL,
 amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(company_id, id),
 CHECK (from_date <= to_date),
 CHECK ((source = 'RUN') = (run_id IS NOT NULL)),
 FOREIGN KEY(company_id, asset_id) REFERENCES fixed_assets(company_id, id),
 FOREIGN KEY(company_id, run_id) REFERENCES depreciation_runs(company_id, id),
 FOREIGN KEY(company_id, journal_entry_id) REFERENCES journal_entries(company_id, id)
);
CREATE UNIQUE INDEX uq_fixed_asset_depreciation_run ON fixed_asset_depreciation(company_id, run_id, asset_id) WHERE run_id IS NOT NULL;
CREATE INDEX idx_fixed_asset_depreciation_asset ON fixed_asset_depreciation(company_id, asset_id, to_date);
