-- ADR-0211: the Loadout model (per-slot item pins) never had a writer. The match kit is the
-- inventory carried at entry (ADR-0203), so the table is dropped. V5 stays untouched.
DROP TABLE IF EXISTS loadouts;
