import React from 'react';
import { useNavigate } from 'react-router-dom';

interface SubagentJumpLinkProps {
  /** Child sessionId resolved by backend SubagentSessionResolver. */
  targetSessionId: string;
}

/**
 * R3-WN2: rendered ONLY by ToolSpanDetailView when toolName === 'SubAgent'
 * AND `subagentSessionId` is non-null. Do not render this from LLM span paths
 * — see plan §8.6.
 */
const SubagentJumpLink: React.FC<SubagentJumpLinkProps> = ({ targetSessionId }) => {
  const navigate = useNavigate();
  const shortId = targetSessionId.slice(0, 8);

  return (
    <button
      type="button"
      className="obs-subagent-jump"
      onClick={() => navigate(`/sessions/${targetSessionId}`)}
      aria-label={`Jump to child session ${targetSessionId}`}
    >
      <span aria-hidden="true">→</span>
      <span>子 session: </span>
      <span className="obs-subagent-jump-id mono-sm">[{shortId}]</span>
    </button>
  );
};

export default SubagentJumpLink;
