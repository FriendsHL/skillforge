import React, { useMemo } from 'react';
import {
  Drawer,
  Descriptions,
  Tag,
  Typography,
  Space,
  Collapse,
  Tooltip,
  Empty,
} from 'antd';
import { Link } from 'react-router-dom';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import type { OptimizationEventDto } from '../../api/attribution';
import { stageColor, surfaceColor, riskColor } from './stageStyle';
import { SkillEvolutionPanel } from '../skills/SkillEvolutionPanel';

dayjs.extend(relativeTime);

const { Text, Paragraph } = Typography;

export interface EventDetailDrawerProps {
  event: OptimizationEventDto | null;
  open: boolean;
  onClose: () => void;
  currentUserId?: number;
}

function fmtAbsolute(iso: string | null | undefined): string {
  if (!iso) return '';
  return dayjs(iso).format('YYYY-MM-DD HH:mm:ss');
}

function fmtRelative(iso: string | null | undefined): string {
  if (!iso) return '—';
  return dayjs(iso).fromNow();
}

/**
 * V3 ATTRIBUTION-AGENT Phase 1.5 — drawer presenting the full
 * OptimizationEventDto plus the embedded SkillEvolutionPanel context (only
 * when the event is tied to a skill candidate with a known sourceAgentId).
 *
 * Pure presentational: parent owns open/close + reload-on-WS.
 */
const EventDetailDrawer: React.FC<EventDetailDrawerProps> = ({
  event,
  open,
  onClose,
  currentUserId,
}) => {
  const headerTitle = useMemo(() => {
    if (!event) return 'Optimization event';
    return (
      <Space size="small" wrap>
        <span>Event #{event.id}</span>
        <Tag color={stageColor(event.stage)}>{event.stage}</Tag>
        <Tag color={surfaceColor(event.surfaceType)}>{event.surfaceType}</Tag>
        {/* risk is nullable on BE (sentinel rows) — omit the chip rather than
            rendering an empty / "null" string Tag. */}
        {event.risk && <Tag color={riskColor(event.risk)}>{event.risk}</Tag>}
      </Space>
    );
  }, [event]);

  if (!event) {
    return (
      <Drawer open={open} onClose={onClose} width={900} title="Event">
        <Empty description="No event selected" />
      </Drawer>
    );
  }

  // Confidence is a BigDecimal 0..1 on the wire (nullable for sentinel rows).
  // Display as percent for the operator (e.g. 0.82 → "82%") so it's directly
  // comparable to PatternList.
  const confidencePct =
    typeof event.confidence === 'number' && Number.isFinite(event.confidence)
      ? `${Math.round(event.confidence * 100)}%`
      : '—';

  // SkillEvolutionPanel needs (skillId, agentId, currentUserId). We only have
  // the candidateSkillId — that's enough; the panel itself fetches evolution
  // runs scoped to (skillId, agentId).
  const showEvolutionPanel =
    event.candidateSkillId != null && event.agentId != null;

  return (
    <Drawer
      open={open}
      onClose={onClose}
      width={900}
      title={headerTitle}
      destroyOnClose
    >
      {/* Description — the operator-readable summary of the proposal.
          Nullable on BE for sentinel rows; fall back to a dim placeholder so
          the block doesn't collapse into a zero-height background strip. */}
      <Paragraph
        style={{
          background: 'var(--bg-2, rgba(0,0,0,0.04))',
          padding: '12px 16px',
          borderRadius: 6,
          marginBottom: 20,
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
        }}
      >
        {event.description ?? (
          <Text type="secondary">(no description — sentinel / pre-proposal row)</Text>
        )}
      </Paragraph>

      <Descriptions
        size="small"
        column={2}
        bordered
        style={{ marginBottom: 20 }}
        labelStyle={{ width: 150, fontSize: 12 }}
        contentStyle={{ fontSize: 12 }}
      >
        <Descriptions.Item label="Pattern">
          <Link
            to={`/insights/patterns?id=${event.patternId}`}
            style={{ fontFamily: 'var(--font-mono)' }}
          >
            #{event.patternId}
          </Link>
        </Descriptions.Item>
        <Descriptions.Item label="Agent">
          <Text style={{ fontFamily: 'var(--font-mono)' }}>#{event.agentId}</Text>
        </Descriptions.Item>
        <Descriptions.Item label="Change type">
          {event.changeType ? (
            <Text>{event.changeType}</Text>
          ) : (
            <Text type="secondary">—</Text>
          )}
        </Descriptions.Item>
        <Descriptions.Item label="Confidence">
          {confidencePct === '—' ? (
            <Text type="secondary">—</Text>
          ) : (
            <Text strong>{confidencePct}</Text>
          )}
        </Descriptions.Item>
        <Descriptions.Item label="Expected impact" span={2}>
          {event.expectedImpact ? (
            <Text>{event.expectedImpact}</Text>
          ) : (
            <Text type="secondary">—</Text>
          )}
        </Descriptions.Item>
        <Descriptions.Item label="Candidate skill">
          {event.candidateSkillId != null ? (
            <Link
              to="/skills"
              style={{ fontFamily: 'var(--font-mono)' }}
            >
              #{event.candidateSkillId}
            </Link>
          ) : (
            <Text type="secondary">—</Text>
          )}
        </Descriptions.Item>
        <Descriptions.Item label="Candidate prompt version">
          {event.candidatePromptVersionId != null ? (
            <Text style={{ fontFamily: 'var(--font-mono)' }}>
              #{event.candidatePromptVersionId}
            </Text>
          ) : (
            <Text type="secondary">—</Text>
          )}
        </Descriptions.Item>
        <Descriptions.Item label="A/B run">
          {event.abRunId != null ? (
            <Text style={{ fontFamily: 'var(--font-mono)' }}>#{event.abRunId}</Text>
          ) : (
            <Text type="secondary">—</Text>
          )}
        </Descriptions.Item>
        <Descriptions.Item label="Canary rollout">
          {event.canaryId != null ? (
            <Text style={{ fontFamily: 'var(--font-mono)' }}>
              #{event.canaryId}
            </Text>
          ) : (
            <Text type="secondary">—</Text>
          )}
        </Descriptions.Item>
        <Descriptions.Item label="Attribution session">
          {event.attributionSessionId ? (
            // Reuse Traces page query input (matches PatternDetailDrawer idiom).
            <Link
              to={`/traces?q=${encodeURIComponent(event.attributionSessionId)}`}
              style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}
            >
              {event.attributionSessionId.slice(0, 16)}…
            </Link>
          ) : (
            <Text type="secondary">—</Text>
          )}
        </Descriptions.Item>
        <Descriptions.Item label="Cooldown expires">
          {event.cooldownExpiresAt ? (
            <Tooltip title={fmtAbsolute(event.cooldownExpiresAt)}>
              {fmtRelative(event.cooldownExpiresAt)}
            </Tooltip>
          ) : (
            <Text type="secondary">—</Text>
          )}
        </Descriptions.Item>
        <Descriptions.Item label="Created">
          <Tooltip title={fmtAbsolute(event.createdAt)}>
            {fmtRelative(event.createdAt)}
          </Tooltip>
        </Descriptions.Item>
        <Descriptions.Item label="Last updated">
          <Tooltip title={fmtAbsolute(event.updatedAt)}>
            {fmtRelative(event.updatedAt)}
          </Tooltip>
        </Descriptions.Item>
      </Descriptions>

      {showEvolutionPanel && (
        <Collapse
          ghost
          items={[
            {
              key: 'evolution',
              label: (
                <Text strong style={{ fontSize: 13 }}>
                  Skill evolution context
                  <Text type="secondary" style={{ fontWeight: 400, marginLeft: 8 }}>
                    (skill #{event.candidateSkillId})
                  </Text>
                </Text>
              ),
              children: (
                <SkillEvolutionPanel
                  skillId={event.candidateSkillId as number}
                  agentId={event.agentId}
                  currentUserId={currentUserId}
                />
              ),
            },
          ]}
        />
      )}
    </Drawer>
  );
};

export default EventDetailDrawer;
