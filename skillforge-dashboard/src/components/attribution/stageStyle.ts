/**
 * V3 ATTRIBUTION-AGENT Phase 1.5 — shared stage / surface / risk color maps.
 *
 * Single source of truth for Tag colors so the queue, the timeline, and the
 * detail drawer never disagree. Keep in sync with
 * {@code OptimizationEventEntity.STAGE_*} (see api/attribution.ts AttributionStage).
 *
 * Semantic colors (design.md §Required Quality 5):
 *   gold  — pending operator action
 *   blue  — work in flight on the BE
 *   green — terminal success / progressed
 *   red   — failure / terminal stop
 *   purple — pipeline progression markers
 *   gray  — neutral / inactive / rolled back
 */

/** Tag color for every stage in the V3 lifecycle. Unknown values fall through. */
export const STAGE_COLORS: Record<string, string> = {
  dispatch_initiated: 'default',
  proposal_pending: 'gold',
  proposal_approved: 'cyan',
  proposal_rejected: 'default',
  candidate_generating: 'blue',
  candidate_ready: 'green',
  candidate_failed: 'red',
  candidate_created: 'green',
  ab_running: 'geekblue',
  ab_passed: 'green',
  ab_failed: 'red',
  canary_started: 'purple',
  promoted: 'green',
  rolled_back: 'default',
  verified: 'green',
};

/** Surface chip color (matches PatternList.tsx for visual consistency). */
export const SURFACE_COLORS: Record<string, string> = {
  skill: 'geekblue',
  prompt: 'purple',
  behavior_rule: 'magenta',
  other: 'orange',
  unclear: 'default',
};

/** Risk chip color — semantic high-watermark gradient. */
export const RISK_COLORS: Record<string, string> = {
  low: 'green',
  medium: 'gold',
  high: 'red',
};

export function stageColor(stage: string | null | undefined): string {
  if (!stage) return 'default';
  return STAGE_COLORS[stage] ?? 'default';
}

export function surfaceColor(surface: string | null | undefined): string {
  if (!surface) return 'default';
  return SURFACE_COLORS[surface] ?? 'default';
}

export function riskColor(risk: string | null | undefined): string {
  if (!risk) return 'default';
  return RISK_COLORS[risk] ?? 'default';
}
