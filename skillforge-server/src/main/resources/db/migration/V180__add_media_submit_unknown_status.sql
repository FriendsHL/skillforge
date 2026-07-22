ALTER TABLE t_media_generation_job
    DROP CONSTRAINT chk_media_job_status;

ALTER TABLE t_media_generation_job
    ADD CONSTRAINT chk_media_job_status CHECK (status IN (
        'CREATED', 'SUBMITTING', 'QUEUED', 'RUNNING', 'DOWNLOADING', 'PROCESSING', 'READY',
        'SUBMIT_FAILED', 'SUBMIT_UNKNOWN', 'GENERATION_FAILED', 'DOWNLOAD_FAILED', 'PROCESSING_FAILED',
        'CANCELLED', 'EXPIRED'));
