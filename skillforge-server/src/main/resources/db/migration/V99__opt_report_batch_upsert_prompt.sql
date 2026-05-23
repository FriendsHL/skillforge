-- OPT-REPORT-V1 V1.0 followup (2026-05-23): fix batch tracking gap.
--
-- V97 seeded report-generator + session-batch-annotator with prompts that
-- assumed t_opt_report_batch rows were pre-inserted by the parent. They are
-- not — no tool exists for that. SubAgent calls RecordBatchAnnotations →
-- UPDATE → "OptReportBatch not found" → batch metadata lost.
--
-- Fix: RecordBatchAnnotations Tool was updated (Java code) to UPSERT, accepting
-- reportId + sessionIds[] when batchId doesn't yet exist. This migration teaches
-- the prompts to pass those args.
--
-- Parent prompt (STEP 3): make sure the SubAgent task message includes both
--   reportId and sessionIds explicitly so the worker can echo them back.
-- Child prompt (STEP B): pass reportId + sessionIds to RecordBatchAnnotations.
--
-- The kickoff task string already mentions reportId + sessionIds; we just
-- tighten the worker's STEP B instruction so it actually forwards them to the
-- Tool call (the prompt previously only mentioned batchId + count).

UPDATE t_agent
SET system_prompt = replace(
    system_prompt,
    'RecordBatchAnnotations(batchId=<新 UUID>, annotationsWrittenCount=<N>, status=''completed'')',
    'RecordBatchAnnotations(batchId=<新 UUID>, reportId=<R>, sessionIds=[<sid1>,<sid2>,...], annotationsWrittenCount=<N>, status=''completed'')'
),
    updated_at = NOW()
WHERE name = 'report-generator';

UPDATE t_agent
SET system_prompt = replace(
    system_prompt,
    'RecordBatchAnnotations(batchId=',
    'RecordBatchAnnotations(reportId=<R>, sessionIds=<your assigned IDs>, batchId='
),
    updated_at = NOW()
WHERE name = 'session-batch-annotator';
