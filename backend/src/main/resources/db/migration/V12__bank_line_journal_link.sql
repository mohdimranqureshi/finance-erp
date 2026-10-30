-- A statement line can be explained by an accepted AI-categorised journal (bank charges, interest) instead of a payment.
ALTER TABLE bank_statement_lines ADD COLUMN journal_entry_id BIGINT REFERENCES journal_entries(id);
CREATE INDEX idx_bank_lines_journal ON bank_statement_lines(journal_entry_id) WHERE journal_entry_id IS NOT NULL;
