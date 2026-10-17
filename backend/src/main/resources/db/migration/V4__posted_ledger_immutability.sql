-- Application-level guardrails: posted ledger entries/lines and audit events are append-only.
-- Database owners can still disable triggers; production DB credentials must be least-privilege.
CREATE OR REPLACE FUNCTION prevent_posted_journal_mutation() RETURNS trigger AS $$
DECLARE old_status VARCHAR(20);
BEGIN
  IF TG_OP = 'INSERT' THEN RETURN NEW; END IF;
  SELECT status INTO old_status FROM journal_entries WHERE company_id=OLD.company_id AND id=OLD.id;
  IF old_status IN ('POSTED','REVERSED') THEN
    IF TG_OP = 'UPDATE'
       AND (to_jsonb(NEW) - 'reversed_by_journal_id') = (to_jsonb(OLD) - 'reversed_by_journal_id') THEN
      RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Posted journal entries are immutable; create a reversal instead.' USING ERRCODE='55000';
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_posted_journal_immutable
BEFORE UPDATE OR DELETE ON journal_entries
FOR EACH ROW EXECUTE FUNCTION prevent_posted_journal_mutation();

CREATE OR REPLACE FUNCTION prevent_posted_journal_line_mutation() RETURNS trigger AS $$
DECLARE old_status VARCHAR(20);
DECLARE new_status VARCHAR(20);
BEGIN
  IF TG_OP IN ('UPDATE','DELETE') THEN
    SELECT status INTO old_status FROM journal_entries WHERE company_id=OLD.company_id AND id=OLD.journal_entry_id;
    IF old_status IN ('POSTED','REVERSED') THEN
      RAISE EXCEPTION 'Lines of posted journals are immutable; create a reversal instead.' USING ERRCODE='55000';
    END IF;
  END IF;
  IF TG_OP IN ('INSERT','UPDATE') THEN
    SELECT status INTO new_status FROM journal_entries WHERE company_id=NEW.company_id AND id=NEW.journal_entry_id;
    IF new_status IN ('POSTED','REVERSED') THEN
      RAISE EXCEPTION 'Lines of posted journals are immutable; create a reversal instead.' USING ERRCODE='55000';
    END IF;
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_posted_journal_lines_immutable
BEFORE INSERT OR UPDATE OR DELETE ON journal_lines
FOR EACH ROW EXECUTE FUNCTION prevent_posted_journal_line_mutation();

CREATE OR REPLACE FUNCTION prevent_audit_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'Audit events are append-only.' USING ERRCODE='55000';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_audit_events_immutable
BEFORE UPDATE OR DELETE ON audit_events
FOR EACH ROW EXECUTE FUNCTION prevent_audit_mutation();
