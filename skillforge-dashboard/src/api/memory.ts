import api from './client';

// Memory API
export type MemoryLifecycleStatus = 'ACTIVE' | 'STALE' | 'ARCHIVED';

export interface MemoryStats {
  active: number;
  stale: number;
  archived: number;
  capacityCap: number;
}

export const getMemories = (userId: number, type?: string, status?: MemoryLifecycleStatus) =>
  api.get('/memories', { params: { userId, type, status } });
export const searchMemories = (userId: number, keyword: string) =>
  api.get('/memories/search', { params: { userId, keyword } });
export interface CreateMemoryRequest {
  userId: number;
  type: string;
  content: string;
  source?: string;
}

export interface UpdateMemoryRequest {
  content?: string;
  type?: string;
  source?: string;
}

export const createMemory = (data: CreateMemoryRequest) => api.post('/memories', data);
export const updateMemory = (id: number, data: UpdateMemoryRequest) => api.put(`/memories/${id}`, data);
export const deleteMemory = (id: number) => api.delete(`/memories/${id}`);
export const updateMemoryStatus = (id: number, userId: number, status: MemoryLifecycleStatus) =>
  api.patch(`/memories/${id}/status`, { status }, { params: { userId } });
export const batchArchiveMemories = (userId: number, ids: number[]) =>
  api.post('/memories/batch-archive', { ids }, { params: { userId } });
export const batchRestoreMemories = (userId: number, ids: number[]) =>
  api.post('/memories/batch-restore', { ids }, { params: { userId } });
export const batchUpdateMemoryStatus = (userId: number, ids: number[], status: MemoryLifecycleStatus) =>
  api.post('/memories/batch-status', { ids, status }, { params: { userId } });
export const batchDeleteMemories = (userId: number, ids: number[]) =>
  api.delete('/memories/batch', { data: { ids }, params: { userId } });
export const getMemoryStats = (userId: number) =>
  api.get<MemoryStats>('/memories/stats', { params: { userId } });

// MEMORY-DREAM-CONSOLIDATION-V2.5 — manual trigger for the nightly cron.
export interface MemoryConsolidationTotals {
  dedupArchived: number;
  ttlArchived: number;
  staleTransitioned: number;
  capacityDemoted: number;
  expiredDeleted: number;
  activeAfter: number;
}
export interface MemoryConsolidationResponse {
  ok: boolean;
  ran: string;
  at: string;
  userIdFilter: number | null;
  eligible: number;
  succeeded: number;
  failed: number;
  totals?: MemoryConsolidationTotals;
}
export const triggerMemoryConsolidation = (userId?: number) =>
  api.post<MemoryConsolidationResponse>(
    '/admin/memory/consolidation/run-once',
    null,
    { params: userId != null ? { userId } : {} },
  );
