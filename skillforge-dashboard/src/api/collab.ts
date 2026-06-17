import api from './client';

// Collab Run API
export const getCollabRuns = () => api.get('/collab-runs');
export const getCollabRunMembers = (collabRunId: string) => api.get(`/collab-runs/${collabRunId}/members`);
export const getCollabRunSummary = (collabRunId: string) => api.get(`/collab-runs/${collabRunId}/summary`);
export const getCollabRunTraces = (collabRunId: string) => api.get(`/collab-runs/${collabRunId}/traces`);
