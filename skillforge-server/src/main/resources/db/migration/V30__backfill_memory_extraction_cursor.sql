-- Memory v2 PR-3 follow-up: make the new incremental extraction cursor compatible
-- with sessions that were already extracted before V29 introduced
-- t_session.last_extracted_message_seq.
--
-- Without this backfill, any legacy session with digest_extracted_at set and the
-- V29 default cursor=0 would look like it had unextracted messages at seq_no>0,
-- causing the idle/daily scanners to re-extract most of its historical row store.
--
-- New or never-extracted sessions keep cursor=0. SessionDigestExtractor treats
-- (cursor=0, digest_extracted_at IS NULL) as "start before seq 0" so the first
-- extraction still includes the first row.
UPDATE t_session s
   SET last_extracted_message_seq = COALESCE((
       SELECT MAX(m.seq_no)
         FROM t_session_message m
        WHERE m.session_id = s.id
          AND m.msg_type = 'NORMAL'
          AND m.pruned_at IS NULL
   ), 0)
 WHERE s.digest_extracted_at IS NOT NULL
   AND s.last_extracted_message_seq = 0;
