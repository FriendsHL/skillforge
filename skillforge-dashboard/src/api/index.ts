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
export const getSessionMessages = (id: string) => api.get(`/chat/sessions/${id}/messages`);

// Chat API
export const sendMessage = (sessionId: string, data: { message: string; userId: number }) =>
  api.post(`/chat/${sessionId}`, data);

// Skill API
export const getSkills = () => api.get('/skills');
export const uploadSkill = (file: File, ownerId: number) => {
  const form = new FormData();
  form.append('file', file);
  form.append('ownerId', String(ownerId));
  return api.post('/skills/upload', form);
};
export const deleteSkill = (id: number) => api.delete(`/skills/${id}`);

// Dashboard API
export const getDashboardOverview = () => api.get('/dashboard/overview');
