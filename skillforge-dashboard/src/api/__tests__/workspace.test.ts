import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../client', () => {
  const get = vi.fn();
  return { default: { get } };
});

import api from '../client';
import { getWorkspaceContent, getWorkspaceEntries } from '../workspace';

const mockedGet = (api as unknown as { get: ReturnType<typeof vi.fn> }).get;

describe('workspace API client', () => {
  beforeEach(() => {
    mockedGet.mockReset();
  });

  it('lists one relative directory through the protected API client', async () => {
    mockedGet.mockResolvedValueOnce({
      data: {
        rootLabel: 'SkillForge', path: 'docs/guides', parentPath: 'docs',
        truncated: false, entries: [],
      },
    });

    await getWorkspaceEntries('docs/guides');

    expect(mockedGet).toHaveBeenCalledWith('/workspace/entries', {
      params: { path: 'docs/guides' },
    });
  });

  it('requests bounded file content by relative path', async () => {
    mockedGet.mockResolvedValueOnce({
      data: {
        path: 'README.md', content: '# SkillForge', sizeBytes: 12,
        truncated: false, binary: false,
      },
    });

    await getWorkspaceContent('README.md');

    expect(mockedGet).toHaveBeenCalledWith('/workspace/content', {
      params: { path: 'README.md' },
    });
  });
});
