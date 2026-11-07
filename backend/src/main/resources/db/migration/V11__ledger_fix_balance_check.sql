-- Fixes the balance check from V10. PL/pgSQL resolves every field referenced in an expression,
-- so "CASE ... THEN NEW.id ELSE NEW.entry_id END" failed on journal_entries rows, which have no
-- entry_id, and every posting was refused at commit. Pick the field with IF instead.

CREATE OR REPLACE FUNCTION ledger.check_entry_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    target  uuid;
    debits  bigint;
    credits bigint;
    lines   integer;
BEGIN
    IF TG_TABLE_NAME = 'journal_entries' THEN
        target := NEW.id;
    ELSE
        target := NEW.entry_id;
    END IF;

    SELECT coalesce(sum(debit_minor), 0), coalesce(sum(credit_minor), 0), count(*)
      INTO debits, credits, lines
      FROM ledger.journal_lines
     WHERE entry_id = target;
    IF lines < 2 OR debits <> credits THEN
        RAISE EXCEPTION 'ledger entry % is not balanced (debits %, credits %, lines %)', target, debits, credits, lines
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;
