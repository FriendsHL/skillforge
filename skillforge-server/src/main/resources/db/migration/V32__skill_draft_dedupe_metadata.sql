-- =====================================================================
-- V32__skill_draft_dedupe_metadata.sql
-- Source: P1 Code Judge r1 (B-FE-3) — persist similarity / merge candidate
-- on t_skill_draft so the FE Modal.confirm + forceCreate flow can work.
--
-- Why a fresh migration (not amend V31): V31 has already applied to dev DBs;
-- amending an applied migration would force operators to clear flyway history.
-- =====================================================================

ALTER TABLE t_skill_draft ADD COLUMN similarity DOUBLE PRECISION NULL;
ALTER TABLE t_skill_draft ADD COLUMN merge_candidate_id BIGINT NULL;
ALTER TABLE t_skill_draft ADD COLUMN merge_candidate_name VARCHAR(256) NULL;

-- merge_candidate_id is *informational* (it can point to either a t_skill row
-- or a sibling t_skill_draft row). Intentionally no FK; rows can be deleted out
-- from under the candidate without breaking drafts.
