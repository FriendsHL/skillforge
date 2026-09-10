import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

import api from '../client';
import {
  getUnknownOutcomeTarget,
  isUnknownOutcomeTarget,
  isUnknownOutcomeResolutionAck,
  resolveUnknownOutcome,
  type UnknownOutcomeResolutionRequest,
} from '../chat';

const mockedApi = api as unknown as {
  get: ReturnType<typeof vi.fn>;
  post: ReturnType<typeof vi.fn>;
};

describe('unknown outcome API client', () => {
  beforeEach(() => {
    mockedApi.get.mockReset();
    mockedApi.post.mockReset();
  });

  it('uses the authenticated discovery endpoint without a client-selected actor', async () => {
    mockedApi.get.mockResolvedValueOnce({ status: 204 });
    await getUnknownOutcomeTarget('session-1');
    expect(mockedApi.get).toHaveBeenCalledWith(
      '/sessions/session-1/tool-attempts/unknown-outcome',
    );
  });

  it('posts the closed command as the bare request body', async () => {
    const command: UnknownOutcomeResolutionRequest = {
      resolutionRequestId: '94bbb1a1-7135-4029-8a46-5b389ac813bd',
      expectedHistoryEpoch: 3,
      expectedExecutionGeneration: 8,
      expectedExecutionFence: 13,
      action: 'CONTINUE_CURRENT_TIMELINE',
      reason: 'Verified external state',
      inboxDispositions: [],
    };
    mockedApi.post.mockResolvedValueOnce({ data: {} });
    await resolveUnknownOutcome('session-1', 41, command);
    expect(mockedApi.post).toHaveBeenCalledWith(
      '/sessions/session-1/tool-attempts/41/resolve-unknown',
      command,
    );
  });

  it('accepts only the exact current-Session discovery DTO', () => {
    const target = {
      sessionId: 'session-1',
      attemptId: 41,
      historyEpoch: 3,
      executionGeneration: 8,
      executionFence: 13,
      state: 'UNCERTAIN_PENDING_RESOLUTION',
      actorAuthority: 'OWNER',
      calls: [{
        providerOrdinal: 0,
        toolUseId: 'tool-use-1',
        toolName: 'ShellTool',
        input: '{"command":"</pre><script>alert(1)</script>"}',
      }],
      inboxIds: ['0a9d6af1-9fc1-45d8-85c7-37c4acee7051'],
    };
    expect(isUnknownOutcomeTarget(target, 'session-1')).toBe(true);
    expect(isUnknownOutcomeTarget({ ...target, sessionId: 'other' }, 'session-1')).toBe(false);
    expect(isUnknownOutcomeTarget({ ...target, toolInput: 'secret' }, 'session-1')).toBe(false);
    expect(isUnknownOutcomeTarget({ ...target, actorAuthority: 'PLATFORM_ADMIN' }, 'session-1')).toBe(false);
    expect(isUnknownOutcomeTarget({
      ...target,
      calls: [{ ...target.calls[0], providerOrdinal: 1 }],
    }, 'session-1')).toBe(false);
    expect(isUnknownOutcomeTarget({
      ...target,
      calls: [{ ...target.calls[0], input: '<script>not-json</script>' }],
    }, 'session-1')).toBe(false);
    expect(isUnknownOutcomeTarget({ ...target, inboxIds: [...target.inboxIds, ...target.inboxIds] }, 'session-1')).toBe(false);
  });

  it('rejects stale, cross-Session, or extended acknowledgement DTOs', () => {
    const target = {
      sessionId: 'session-1', attemptId: 41, historyEpoch: 3,
      executionGeneration: 8, executionFence: 13,
      state: 'UNCERTAIN_PENDING_RESOLUTION' as const,
      actorAuthority: 'ADMIN' as const,
      calls: [{
        providerOrdinal: 0, toolUseId: 'tool-use-1', toolName: 'ShellTool', input: '{}',
      }],
      inboxIds: [],
    };
    const command: UnknownOutcomeResolutionRequest = {
      resolutionRequestId: '94bbb1a1-7135-4029-8a46-5b389ac813bd',
      expectedHistoryEpoch: 3, expectedExecutionGeneration: 8,
      expectedExecutionFence: 13, action: 'CONTINUE_CURRENT_TIMELINE',
      reason: 'Verified external state', inboxDispositions: [],
    };
    const ack = {
      resolutionRequestId: command.resolutionRequestId,
      sessionId: target.sessionId,
      attemptId: target.attemptId,
      stepId: 'e00d6df5-c43b-4696-97c2-902fc9d3c788',
      historyEpoch: 3,
      executionGeneration: 8,
      executionFence: 13,
      actorAuthority: 'ADMIN',
      action: command.action,
      resultBatchId: 'f4f21af0-2755-4ae3-866e-284fe6eefcec',
      outcomeState: 'RESOLVED_UNKNOWN',
      inboxDispositions: [],
      postActionState: 'PENDING',
      restorePreparing: false,
      auditId: 12,
      resolvedAt: '2026-09-04T00:00:00Z',
    };
    expect(isUnknownOutcomeResolutionAck(ack, target, command)).toBe(true);
    expect(isUnknownOutcomeResolutionAck({ ...ack, sessionId: 'other' }, target, command)).toBe(false);
    expect(isUnknownOutcomeResolutionAck({ ...ack, historyEpoch: 4 }, target, command)).toBe(false);
    expect(isUnknownOutcomeResolutionAck({ ...ack, internalHash: 'secret' }, target, command)).toBe(false);
  });
});
