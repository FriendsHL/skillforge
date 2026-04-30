import type { SkillRow, SkillArtifactStatus } from './types';

const ARTIFACT_STATUSES: ReadonlySet<SkillArtifactStatus> = new Set([
  'active',
  'missing',
  'invalid',
  'shadowed',
]);

function parseArtifactStatus(raw: unknown): SkillArtifactStatus | undefined {
  if (typeof raw !== 'string') return undefined;
  return ARTIFACT_STATUSES.has(raw as SkillArtifactStatus)
    ? (raw as SkillArtifactStatus)
    : undefined;
}

export function guessLang(name: string): string {
  const lower = name.toLowerCase();
  if (lower.endsWith('.ts') || lower.includes('typescript')) return 'ts';
  if (lower.endsWith('.py') || lower.includes('python')) return 'py';
  if (lower.endsWith('.sh') || lower.includes('bash') || lower.includes('shell')) return 'sh';
  if (lower.includes('json') || lower.includes('schema')) return 'json';
  return 'md';
}

export function deriveTags(row: Record<string, unknown>): string[] {
  const tags: string[] = [];
  const tools = row.requiredTools;
  if (typeof tools === 'string' && tools.trim()) {
    tools.split(',').forEach(t => { if (t.trim()) tags.push(t.trim()); });
  }
  return tags;
}

export function normalizeSkill(raw: Record<string, unknown>): SkillRow {
  const name = String(raw.name || '');
  // P1-D: backend `source` is now a provenance string ("upload" / "skill-creator"
  // / etc.), no longer the binary 'system'|'custom'. Determine system-ness from
  // the `isSystem` boolean (preferred) or the legacy `system` boolean. The
  // legacy `source === 'system'` fallback is retained only for older payloads
  // still in the cache during rollout.
  const isSystem =
    raw.isSystem === true ||
    raw.system === true ||
    raw.source === 'system';
  const originSource = typeof raw.source === 'string' ? raw.source : undefined;
  const typeRaw = raw.type;
  const type: SkillRow['type'] =
    typeRaw === 'system' || typeRaw === 'runtime'
      ? typeRaw
      : isSystem
        ? 'system'
        : 'runtime';
  return {
    id: raw.id != null ? raw.id as number : name,
    name,
    description: raw.description ? String(raw.description) : undefined,
    source: isSystem ? 'system' : 'custom',
    lang: guessLang(name),
    enabled: raw.enabled !== false,
    system: isSystem,
    requiredTools: raw.requiredTools ? String(raw.requiredTools) : undefined,
    createdAt: raw.createdAt ? String(raw.createdAt) : undefined,
    tags: deriveTags(raw),
    readOnly: raw.readOnly as boolean | undefined,
    toolSchema: raw.toolSchema,
    semver: raw.semver ? String(raw.semver) : undefined,
    parentSkillId: raw.parentSkillId != null ? Number(raw.parentSkillId) : undefined,
    usageCount: raw.usageCount != null ? Number(raw.usageCount) : undefined,
    successCount: raw.successCount != null ? Number(raw.successCount) : undefined,
    failureCount: raw.failureCount != null ? Number(raw.failureCount) : undefined,
    // P1-D governance fields. `artifactStatus` defaults to 'active' for legacy
    // rows missing this field (matches backend SkillDto.from default).
    isSystem,
    artifactStatus: parseArtifactStatus(raw.artifactStatus) ?? 'active',
    skillPath: typeof raw.skillPath === 'string' ? raw.skillPath : undefined,
    shadowedBy: typeof raw.shadowedBy === 'string' ? raw.shadowedBy : undefined,
    lastScannedAt: typeof raw.lastScannedAt === 'string' ? raw.lastScannedAt : undefined,
    originSource,
    type,
  };
}

export function timeAgo(iso?: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  const ms = Date.now() - d.getTime();
  const m = Math.floor(ms / 60000);
  if (m < 1) return 'just now';
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  const days = Math.floor(h / 24);
  return `${days}d ago`;
}
