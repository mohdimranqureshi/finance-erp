-- Opt-in override of the maker-checker control, for companies where one person runs the books.
-- Default FALSE keeps separation of duties enforced for every existing company.
ALTER TABLE companies ADD COLUMN allow_self_approval BOOLEAN NOT NULL DEFAULT FALSE;
