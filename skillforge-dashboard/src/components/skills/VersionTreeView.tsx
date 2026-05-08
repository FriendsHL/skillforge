import React, { useMemo } from 'react';
import { Spin, Tag, Tooltip } from 'antd';
import { useQuery } from '@tanstack/react-query';
import {
  getSkillVersionTree,
  type SkillVersionTreeNode,
  type SkillVersionTreeResponse,
} from '../../api';

interface VersionTreeViewProps {
  skillId: number;
  userId: number;
  currentLiveId?: number; // The ID of the version currently used by agents
  /**
   * V2 §I — clicking a sibling/parent node's "Open" link should swap the
   * drawer's `currentSkillId`. The page-level state owner passes this in.
   * If omitted, "Open" renders as plain text (fallback when the parent
   * can't switch drawers — see SkillDrawer comments).
   */
  onPreview?: (skillId: number) => void;
  onOpenSkill?: (skillId: number) => void;
}

interface RenderableNode extends SkillVersionTreeNode {
  depth: number;
  /** "ancestor" | "current" | "descendant" — drives the row class. */
  bucket: 'ancestor' | 'current' | 'descendant';
}

/**
 * Flatten ancestors → current → descendants into a renderable list with
 * indent depth. We treat the BE's payload as authoritative for ancestor
 * order (root → parent) and rebuild descendants nesting by walking
 * parentSkillId. Descendants whose `parentSkillId` is unknown are pinned to
 * depth 1 under `current` so they don't drop off the tree.
 */
function flattenTree(
  tree: SkillVersionTreeResponse | undefined,
): RenderableNode[] {
  if (!tree) return [];
  const out: RenderableNode[] = [];

  // Ancestors render top-to-bottom from root → immediate parent. Each
  // ancestor sits one level shallower than the next.
  tree.ancestors.forEach((node, idx) => {
    out.push({ ...node, depth: idx, bucket: 'ancestor' });
  });

  const currentDepth = tree.ancestors.length;
  out.push({ ...tree.current, depth: currentDepth, bucket: 'current' });

  // Descendants — group by parentSkillId, walk recursively from `current.id`.
  const byParent = new Map<number, SkillVersionTreeNode[]>();
  tree.descendants.forEach((d) => {
    const p = d.parentSkillId ?? null;
    if (p == null) return;
    const arr = byParent.get(p) ?? [];
    arr.push(d);
    byParent.set(p, arr);
  });

  const visit = (parentId: number, depth: number) => {
    const kids = byParent.get(parentId) ?? [];
    // Stable order: by createdAt asc when available, fallback to id.
    kids.sort((a, b) => {
      if (a.createdAt && b.createdAt) return a.createdAt.localeCompare(b.createdAt);
      return a.id - b.id;
    });
    kids.forEach((k) => {
      out.push({ ...k, depth, bucket: 'descendant' });
      visit(k.id, depth + 1);
    });
  };
  visit(tree.current.id, currentDepth + 1);

  // Orphan descendants (parent unknown / not in `descendants` map) — pin
  // to currentDepth+1 so they're still surfaced. Rare but possible if the
  // BE's BFS truncated.
  const placedIds = new Set(out.map((n) => n.id));
  tree.descendants
    .filter((d) => !placedIds.has(d.id))
    .forEach((d) => {
      out.push({ ...d, depth: currentDepth + 1, bucket: 'descendant' });
    });

  return out;
}

const formatScore = (s: number | null | undefined): string => {
  if (s == null) return '—';
  return Math.round(s).toString();
};

const formatDate = (iso: string | undefined): string => {
  if (!iso) return '';
  // Match other panels: YYYY-MM-DD only (the precision the row needs).
  return iso.slice(0, 10);
};

const Row: React.FC<{
  node: RenderableNode;
  currentLiveId?: number;
  onView?: (skillId: number) => void;
  onOpenSkill?: (skillId: number) => void;
}> = React.memo(({ node, currentLiveId, onView, onOpenSkill }) => {
  const isCurrent = node.bucket === 'current';
  const indent = node.depth * 18;
  return (
    <div
      data-testid="version-tree-row"
      data-bucket={node.bucket}
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        padding: '6px 10px',
        paddingLeft: 10 + indent,
        borderLeft: isCurrent
          ? '2px solid var(--accent-primary, #6366f1)'
          : '2px solid transparent',
        background: isCurrent ? 'var(--bg-hover, #1d1d22)' : 'transparent',
        borderRadius: 4,
        fontSize: 12,
        fontFamily: 'var(--font-mono, monospace)',
        color: isCurrent
          ? 'var(--text-primary, #e7e7ea)'
          : 'var(--fg-3, #a8a8b1)',
      }}
    >
      <span style={{ opacity: 0.5, width: 16, flexShrink: 0 }}>
        {node.depth > 0 ? '└─' : '▶'}
      </span>
      <div 
        style={{ 
          display: 'flex', 
          alignItems: 'center', 
          gap: 8, 
          flex: 1, 
          minWidth: 0,
          cursor: 'pointer',
          padding: '4px 8px',
          borderRadius: 6,
          background: isCurrent ? 'rgba(99, 102, 241, 0.1)' : 'transparent',
          border: isCurrent ? '1px solid rgba(99, 102, 241, 0.3)' : '1px solid transparent'
        }}
        onClick={() => {
          if (onView) onView(node.id);
          else if (onOpenSkill) onOpenSkill(node.id);
        }}
      >
        {/* Version Number */}
        <span style={{ 
          fontWeight: isCurrent ? 700 : 600, 
          fontFamily: 'monospace', 
          fontSize: 13, 
          whiteSpace: 'nowrap',
          color: isCurrent ? 'var(--accent-primary, #6366f1)' : 'inherit'
        }}>
          {node.semver?.startsWith('v') ? node.semver : `v${node.semver ?? node.id}`}
        </span>

        {/* Live Badge - The only indicator needed for "Agent is using this" */}
        {node.enabled && (
          <span style={{ 
            fontSize: 9, 
            padding: '1px 6px', 
            borderRadius: 10, 
            background: '#52c41a', 
            color: '#fff', 
            fontWeight: 700,
            letterSpacing: 0.5
          }}>
            LIVE
          </span>
        )}

        {/* Skill Name (Truncated) */}
        <span style={{ 
          fontSize: 11, 
          color: 'var(--fg-3)', 
          overflow: 'hidden', 
          textOverflow: 'ellipsis', 
          whiteSpace: 'nowrap',
          marginLeft: 4
        }}>
          {node.name}
        </span>
      </div>
    </div>
  );
});
Row.displayName = 'VersionTreeRow';

/**
 * SKILL-DASHBOARD-POLISH-V2 §I — vertical version-tree view for the drawer.
 * Reads `GET /api/skills/:id/version-tree` and renders ancestors → current →
 * descendants with indent + score. Self-check #3: empty ancestors AND empty
 * descendants → render the current node alone with a small "no related
 * versions" caption (instead of leaving the tab blank).
 */
export const VersionTreeView: React.FC<VersionTreeViewProps> = React.memo(
  ({ skillId, userId, currentLiveId, onView, onOpenSkill }) => {
    const { data, isLoading, isError, error } = useQuery<SkillVersionTreeResponse>({
      queryKey: ['skill-version-tree', skillId, userId],
      queryFn: () => getSkillVersionTree(skillId, userId).then((r) => r.data),
      enabled: skillId != null && !!userId,
      staleTime: 60_000,
      retry: 1,
    });

    const flat = useMemo(() => flattenTree(data), [data]);
    const hasRelations = data
      ? data.ancestors.length > 0 || data.descendants.length > 0
      : false;

    if (isLoading) {
      return (
        <div className="sf-empty-state" style={{ padding: 24 }}>
          <Spin size="small" /> <span style={{ marginLeft: 8 }}>Loading version tree…</span>
        </div>
      );
    }

    if (isError) {
      const msg = (error as Error)?.message ?? 'Failed to load version tree';
      return (
        <div
          className="sf-empty-state"
          style={{ padding: 24, color: 'var(--color-err, #f0616d)' }}
        >
          {msg}
        </div>
      );
    }

    if (!data) {
      return <div className="sf-empty-state">No version data.</div>;
    }

    return (
      <div data-testid="version-tree-view">
        <div
          style={{
            fontSize: 11,
            textTransform: 'uppercase',
            letterSpacing: 0.4,
            color: 'var(--text-muted, #8a8a93)',
            marginBottom: 8,
            fontWeight: 600,
          }}
        >
          Version tree
        </div>
        <div
          style={{
            display: 'flex',
            flexDirection: 'column',
            gap: 2,
            border: '1px solid var(--border-subtle, #2a2a31)',
            borderRadius: 6,
            padding: 6,
            background: 'var(--bg-base, #0f0f10)',
          }}
        >
          {flat.map((node) => (
            <Row
              key={`${node.bucket}-${node.id}`}
              node={node}
              currentLiveId={currentLiveId}
              onView={onView}
              onOpenSkill={onOpenSkill}
            />
          ))}
        </div>

        {!hasRelations && (
          <div
            style={{
              marginTop: 10,
              fontSize: 11,
              color: 'var(--text-muted, #8a8a93)',
              fontStyle: 'italic',
            }}
          >
            No related versions yet — this skill has no parent or forks. Forks
            from the Evolution panel will appear here once promoted.
          </div>
        )}
      </div>
    );
  },
);
VersionTreeView.displayName = 'VersionTreeView';
