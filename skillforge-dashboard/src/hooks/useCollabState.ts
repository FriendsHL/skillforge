import { useEffect, useState } from 'react';

export function useCollabState(activeSessionId: string | undefined) {
  const [collabRunId, setCollabRunId] = useState<string | null>(null);
  const [collabHandle, setCollabHandle] = useState<string | null>(null);
  const [collabLeaderSessionId, setCollabLeaderSessionId] = useState<string | null>(null);
  const [collabRunStatus, setCollabRunStatus] = useState<string | null>(null);

  useEffect(() => {
    setCollabRunId(null);
    setCollabHandle(null);
    setCollabLeaderSessionId(null);
    setCollabRunStatus(null);
  }, [activeSessionId]);

  return {
    collabRunId,
    setCollabRunId,
    collabHandle,
    setCollabHandle,
    collabLeaderSessionId,
    setCollabLeaderSessionId,
    collabRunStatus,
    setCollabRunStatus,
  };
}
