import api from './client';

// User Config API
export const getClaudeMd = (userId: number) => api.get('/user-config/claude-md', { params: { userId } });
export const saveClaudeMd = (userId: number, claudeMd: string) =>
  api.put('/user-config/claude-md', { claudeMd }, { params: { userId } });
