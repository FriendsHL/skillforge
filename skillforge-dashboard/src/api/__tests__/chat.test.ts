import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../client', () => ({
  default: {
    post: vi.fn(),
  },
}));

import api from '../client';
import { cancelChat, sendMessage } from '../chat';

const mockedApi = api as unknown as {
  post: ReturnType<typeof vi.fn>;
};

describe('chat API client', () => {
  beforeEach(() => {
    mockedApi.post.mockReset();
  });

  it('forwards a stable cancellation requestId as a query parameter', async () => {
    mockedApi.post.mockResolvedValueOnce({ data: { status: 'cancelling' } });

    await cancelChat(
      'session-1',
      7,
      '82d83d78-53df-4878-a66d-a3ea261a6055',
    );

    expect(mockedApi.post).toHaveBeenCalledWith(
      '/chat/session-1/cancel',
      null,
      {
        params: {
          userId: 7,
          requestId: '82d83d78-53df-4878-a66d-a3ea261a6055',
        },
      },
    );
  });

  it('keeps requestId optional for feature-flag-off callers', async () => {
    mockedApi.post.mockResolvedValueOnce({ data: { status: 'cancelling' } });

    await cancelChat('session-1', 7);

    expect(mockedApi.post).toHaveBeenCalledWith(
      '/chat/session-1/cancel',
      null,
      { params: { userId: 7 } },
    );
  });

  it('forwards a retry-stable ordered-inbox requestId in the message body', async () => {
    mockedApi.post.mockResolvedValueOnce({
      data: {
        sessionId: 'session-1',
        status: 'scheduled',
        requestId: '53b9e4aa-c8cf-4bad-997f-7f12c6393531',
      },
    });

    await sendMessage('session-1', {
      message: 'queued update',
      userId: 7,
      requestId: '53b9e4aa-c8cf-4bad-997f-7f12c6393531',
    });

    expect(mockedApi.post).toHaveBeenCalledWith('/chat/session-1', {
      message: 'queued update',
      userId: 7,
      requestId: '53b9e4aa-c8cf-4bad-997f-7f12c6393531',
    });
  });
});
