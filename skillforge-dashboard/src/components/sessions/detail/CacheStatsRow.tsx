/**
 * PROMPT-CACHE-MVP Phase 4 — render cache hit / write / hit-rate / break event
 * inside the LlmSpan Meta tab. Composes 1–3 `obs-span-meta-row` rows so the
 * grid in {@link ./LlmSpanDetailView.tsx} stays consistent.
 *
 * Token-accounting model
 * ----------------------
 * Anthropic's wire schema (the only provider that actually triggers
 * meaningful FE caching today via manual `cache_control` breakpoints) splits
 * a prompt into three disjoint buckets that the BE persists separately on
 * `t_llm_span`:
 *   - {@code input_tokens}                — non-cached prompt tokens
 *   - {@code cache_read_input_tokens}     — tokens read from a hot cache entry
 *   - {@code cache_creation_input_tokens} — tokens written to a new cache entry
 *
 * Total prompt tokens = sum of the three.  The hit-rate denominator must be
 * this sum, NOT just `input_tokens` — otherwise high-cache hits exceed 100%
 * and the colour thresholds become meaningless. (r1 review B1.)
 *
 * Caveat for OpenAI-family providers (DeepSeek / Qwen / OpenAI / vLLM):
 * `prompt_tokens` on the wire is the TOTAL (cached portion already included),
 * and `cache_creation` is always 0 in MVP. The BE's `UsageNormalizer.parse`
 * does not currently re-normalise `prompt_tokens` to "non-cached only", so
 * for those providers the same sum DOUBLE-COUNTS the cached portion, under-
 * reporting hit rate. See r2 follow-up note in summary report; addressing
 * this requires either BE normalisation or FE provider-aware branching.
 *
 * Display contract (PRD §3 Phase 4 + team-lead invariants)
 * --------------------------------------------------------
 *   1. `cache` row always renders — three numbers `read / write / prompt`.
 *      Thousands-grouped via `Number.prototype.toLocaleString()`. NULL on
 *      either token field falls back to 0 (backward compat for spans
 *      persisted before V62 / providers that don't emit `cache_creation`).
 *   2. `hit rate` row + colour-coded {@link Tag} render iff
 *      `cacheReadTokens > 0 && promptTokens > 0` (avoid divide-by-zero AND
 *      avoid a misleading "0.0%" badge — at 0% we just show numbers).
 *      Thresholds: `>= 50%` green / `>= 20%` gold / `< 20%` default-grey.
 *      The 50%/20% boundaries are inclusive on the higher band per FE brief.
 *   3. `cache event` row + red {@link Tag} renders iff
 *      `metadata.cache_break === true`. Hover {@link Tooltip} surfaces
 *      `metadata.cache_break_reason` when present, else a generic message.
 */
import React from 'react';
import { Tag, Tooltip } from 'antd';
import type { LlmSpanMetadata } from '../../../types/observability';

export interface CacheStatsRowProps {
  /** Tokens read from the prompt cache (Anthropic
   *  `cache_read_input_tokens` / DeepSeek `prompt_cache_hit_tokens` /
   *  OpenAI-family `prompt_tokens_details.cached_tokens`). NULL = 0. */
  cacheReadTokens?: number | null;
  /** Tokens written to a new cache entry on this turn (Anthropic
   *  `cache_creation_input_tokens`; 0 for auto-cache providers). NULL = 0. */
  cacheCreationTokens?: number | null;
  /** Non-cached prompt tokens (Anthropic `input_tokens`). Caller is
   *  responsible for passing the *non-cached* count, NOT the wire-level
   *  `prompt_tokens` total — this component sums the three buckets to
   *  derive the prompt total used for the hit-rate denominator. */
  nonCachedInputTokens: number;
  /** Free-form telemetry from the BE; consumed for `cache_break` rendering. */
  metadata?: LlmSpanMetadata | null;
}

const formatTokens = (n: number): string => n.toLocaleString();

const formatHitRate = (rate: number): string => `${rate.toFixed(1)}%`;

/** Returns the antd Tag color for the given hit-rate percentage, or `null`
 *  to skip rendering a badge entirely. Caller is responsible for ensuring
 *  rate > 0 (we still defend here for clarity). */
export const hitRateTagColor = (ratePct: number): 'green' | 'gold' | 'default' | null => {
  if (ratePct <= 0) return null;
  if (ratePct >= 50) return 'green';
  if (ratePct >= 20) return 'gold';
  return 'default';
};

const CacheStatsRow: React.FC<CacheStatsRowProps> = ({
  cacheReadTokens,
  cacheCreationTokens,
  nonCachedInputTokens,
  metadata,
}) => {
  const cacheRead = cacheReadTokens ?? 0;
  const cacheCreation = cacheCreationTokens ?? 0;
  const promptTokens = nonCachedInputTokens + cacheRead + cacheCreation;

  const showHitRate = cacheRead > 0 && promptTokens > 0;
  const hitRatePct = showHitRate ? (cacheRead / promptTokens) * 100 : 0;
  const tagColor = showHitRate ? hitRateTagColor(hitRatePct) : null;

  const cacheBroken = metadata?.cache_break === true;
  const breakReason =
    typeof metadata?.cache_break_reason === 'string' && metadata.cache_break_reason.length > 0
      ? metadata.cache_break_reason
      : 'Cache prefix changed at this turn';

  return (
    <>
      <div className="obs-span-meta-row" data-testid="cache-stats-row">
        <span className="obs-span-meta-k">cache</span>
        <span className="obs-span-meta-v mono-sm">
          read {formatTokens(cacheRead)} / write {formatTokens(cacheCreation)} / prompt{' '}
          {formatTokens(promptTokens)}
        </span>
      </div>
      {showHitRate && tagColor && (
        <div className="obs-span-meta-row" data-testid="cache-hit-rate-row">
          <span className="obs-span-meta-k">hit rate</span>
          <span className="obs-span-meta-v">
            <Tag color={tagColor}>{formatHitRate(hitRatePct)}</Tag>
          </span>
        </div>
      )}
      {cacheBroken && (
        <div className="obs-span-meta-row" data-testid="cache-break-row">
          <span className="obs-span-meta-k">cache event</span>
          <span className="obs-span-meta-v">
            <Tooltip title={breakReason}>
              <Tag color="red">cache broken at this turn</Tag>
            </Tooltip>
          </span>
        </div>
      )}
    </>
  );
};

export default CacheStatsRow;
