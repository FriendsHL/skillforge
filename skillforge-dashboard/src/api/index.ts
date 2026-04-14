import axios from 'axios';

const api = axios.create({ baseURL: '/api' });

// Agent API
export const getAgents = () => api.get('/agents');
export const getAgent = (id: number) => api.get(`/agents/${id}`);
export const createAgent = (data: any) => api.post('/agents', data);
export const updateAgent = (id: number, data: any) => api.put(`/agents/${id}`, data);
export const deleteAgent = (id: number) => api.delete(`/agents/${id}`);

// Session API
export const createSession = (data: { userId: number; agentId: number }) => api.post('/chat/sessions', data);
export const getSessions = (userId: number) => api.get(`/chat/sessions?userId=${userId}`);
export const getSessionMessages = (id: string, userId: number) =>
  api.get(`/chat/sessions/${id}/messages`, { params: { userId } });

// Chat API
export const sendMessage = (sessionId: string, data: { message: string; userId: number }) =>
  api.post(`/chat/${sessionId}`, data);
export const cancelChat = (sessionId: string, userId: number) =>
  api.post(`/chat/${sessionId}/cancel`, null, { params: { userId } });
export const answerAsk = (sessionId: string, askId: string, answer: string, userId: number) =>
  api.post(`/chat/${sessionId}/answer`, { askId, answer, userId });
export const setSessionMode = (sessionId: string, mode: 'ask' | 'auto', userId: number) =>
  api.patch(`/chat/sessions/${sessionId}/mode`, { mode }, { params: { userId } });
export const getSession = (sessionId: string, userId: number) =>
  api.get(`/chat/sessions/${sessionId}`, { params: { userId } });
export const getChildSessions = (sessionId: string, userId: number) =>
  api.get(`/chat/sessions/${sessionId}/children`, { params: { userId } });
export const getSubAgentRuns = (sessionId: string, userId: number) =>
  api.get(`/chat/sessions/${sessionId}/subagent-runs`, { params: { userId } });
export const compactSession = (sessionId: string, level: 'full', userId: number, reason?: string) =>
  api.post(`/chat/sessions/${sessionId}/compact`, { level, reason }, { params: { userId } });
export const getCompactions = (sessionId: string, userId: number) =>
  api.get(`/chat/sessions/${sessionId}/compactions`, { params: { userId } });
export const getSessionReplay = (sessionId: string, userId: number) =>
  api.get(`/chat/sessions/${sessionId}/replay`, { params: { userId } });

// Traces API
export const getTraces = (sessionId?: string) =>
  sessionId ? api.get(`/traces`, { params: { sessionId } }) : api.get('/traces');
export const getTraceSpans = (traceId: string) => api.get(`/traces/${traceId}/spans`);

// Skill API
export const getSkills = () => api.get('/skills');
export const getBuiltinSkills = () => api.get('/skills/builtin');
export const uploadSkill = (file: File, ownerId: number) => {
  const form = new FormData();
  form.append('file', file);
  form.append('ownerId', String(ownerId));
  return api.post('/skills/upload', form);
};
export const deleteSkill = (id: number) => api.delete(`/skills/${id}`);
export const getSkillDetail = (id: number | string) => api.get(`/skills/${id}/detail`);
export const toggleSkill = (id: number, enabled: boolean) =>
  api.put(`/skills/${id}/toggle?enabled=${enabled}`);

// Memory API
export const getMemories = (userId: number, type?: string) =>
  api.get('/memories', { params: { userId, type } });
export const searchMemories = (userId: number, keyword: string) =>
  api.get('/memories/search', { params: { userId, keyword } });
export const createMemory = (data: any) => api.post('/memories', data);
export const updateMemory = (id: number, data: any) => api.put(`/memories/${id}`, data);
export const deleteMemory = (id: number) => api.delete(`/memories/${id}`);

// User Config API
export const getClaudeMd = (userId: number) => api.get('/user-config/claude-md', { params: { userId } });
export const saveClaudeMd = (userId: number, claudeMd: string) =>
  api.put('/user-config/claude-md', { claudeMd }, { params: { userId } });

// Collab Run API
export const getCollabRunMembers = (collabRunId: string) => api.get(`/collab-runs/${collabRunId}/members`);
export const getCollabRunSummary = (collabRunId: string) => api.get(`/collab-runs/${collabRunId}/summary`);
export const getCollabRunTraces = (collabRunId: string) => api.get(`/collab-runs/${collabRunId}/traces`);

// Dashboard API
export const getDashboardOverview = () => api.get('/dashboard/overview');
export const getDailyUsage = (days = 30) => api.get(`/dashboard/usage/daily?days=${days}`);
export const getUsageByModel = () => api.get('/dashboard/usage/by-model');
export const getUsageByAgent = () => api.get('/dashboard/usage/by-agent');
