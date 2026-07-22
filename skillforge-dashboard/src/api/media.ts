import api from './client';

export type MediaJobStatus = 'CREATED' | 'SUBMITTING' | 'QUEUED' | 'RUNNING' | 'DOWNLOADING' |
  'PROCESSING' | 'READY' | 'SUBMIT_FAILED' | 'GENERATION_FAILED' | 'DOWNLOAD_FAILED' |
  'PROCESSING_FAILED' | 'CANCELLED' | 'EXPIRED';

export interface MediaJob {
  id: string;
  sessionId: string;
  mediaType: 'video' | 'audio';
  provider: string;
  model: string;
  status: MediaJobStatus;
  resultAttachmentId?: string | null;
  errorCode?: string | null;
  errorMessage?: string | null;
  createdAt: string;
  updatedAt: string;
}

export const getMediaJob = (id: string, userId: number) =>
  api.get<MediaJob>(`/media/jobs/${id}`, { params: { userId } });
export const cancelMediaJob = (id: string, userId: number) =>
  api.post<MediaJob>(`/media/jobs/${id}/cancel`, null, { params: { userId } });

