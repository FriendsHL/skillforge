import React, { useEffect, useState } from 'react';
import { Button, Spin, Tag } from 'antd';
import { cancelMediaJob, getMediaJob, type MediaJob } from '../api/media';
import { getChatAttachmentBlob } from '../api';

interface MediaJobCardProps { jobId: string; userId: number; sessionId: string; }
const TERMINAL = new Set(['READY', 'SUBMIT_FAILED', 'SUBMIT_UNKNOWN', 'GENERATION_FAILED', 'DOWNLOAD_FAILED', 'PROCESSING_FAILED', 'CANCELLED', 'EXPIRED']);

const MediaJobCard: React.FC<MediaJobCardProps> = ({ jobId, userId, sessionId }) => {
  const [job, setJob] = useState<MediaJob | null>(null);
  const [videoUrl, setVideoUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let active = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const load = async () => {
      try {
        const { data } = await getMediaJob(jobId, userId);
        if (!active) return;
        setJob(data); setError(null);
        if (!TERMINAL.has(data.status)) timer = setTimeout(load, 5000);
      } catch { if (active) setError('Unable to load video job'); }
    };
    void load();
    return () => { active = false; if (timer) clearTimeout(timer); };
  }, [jobId, userId]);
  useEffect(() => {
    let active = true; let created: string | null = null;
    if (job?.status === 'READY' && job.resultAttachmentId) {
      getChatAttachmentBlob(job.resultAttachmentId, userId, sessionId).then(({ data }) => {
        if (!active) return; created = URL.createObjectURL(data as Blob); setVideoUrl(created);
      }).catch(() => { if (active) setError('Unable to load generated video'); });
    }
    return () => { active = false; if (created) URL.revokeObjectURL(created); };
  }, [job?.status, job?.resultAttachmentId, userId, sessionId]);
  if (error) return <div className="media-job-card" role="alert">{error}</div>;
  if (!job) return <div className="media-job-card"><Spin size="small" /> Loading video job</div>;
  const canCancel = !TERMINAL.has(job.status);
  return <div className="media-job-card" data-testid={`media-job-${job.id}`}>
    <div><strong>Generated video</strong> <Tag>{job.status.toLowerCase()}</Tag></div>
    <div className="media-job-meta">{job.provider} · {job.model}</div>
    {videoUrl && <video controls preload="metadata" src={videoUrl} style={{ width: '100%', maxWidth: 560, borderRadius: 10 }} />}
    {job.errorMessage && <div role="alert">{job.errorMessage}</div>}
    {canCancel && <Button size="small" onClick={async () => setJob((await cancelMediaJob(job.id, userId)).data)}>Cancel</Button>}
  </div>;
};
export default MediaJobCard;
