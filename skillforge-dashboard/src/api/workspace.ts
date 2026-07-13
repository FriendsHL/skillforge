import api from './client';

export type WorkspaceEntryType = 'directory' | 'file';

export interface WorkspaceEntry {
  name: string;
  path: string;
  type: WorkspaceEntryType;
  sizeBytes?: number;
  modifiedAt?: string;
  previewable: boolean;
}

export interface WorkspaceEntriesResponse {
  rootLabel: string;
  path: string;
  parentPath: string | null;
  truncated: boolean;
  entries: WorkspaceEntry[];
}

export interface WorkspaceContentResponse {
  path: string;
  content?: string;
  sizeBytes: number;
  modifiedAt?: string;
  truncated: boolean;
  binary: boolean;
}

export const getWorkspaceEntries = (path = '') =>
  api.get<WorkspaceEntriesResponse>('/workspace/entries', {
    params: { path },
  });

export const getWorkspaceContent = (path: string) =>
  api.get<WorkspaceContentResponse>('/workspace/content', {
    params: { path },
  });
