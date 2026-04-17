/**
 * N3 — state container for the LifecycleHooksEditor.
 *
 * Design highlights (docs/design-n3-lifecycle-hooks.md §5.2):
 *   - All three editing modes share a single source of truth: `rawJson: string`.
 *   - `parsed` + `errors` derive synchronously from `rawJson` via Zod so the
 *     form / preset modes can trust validated data.
 *   - Backend metadata (events + presets) is fetched via TanStack Query and
 *     falls back to local constants if the API is unavailable.
 */

import { useCallback, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  getLifecycleHookEvents,
  getLifecycleHookPresets,
  type LifecycleHookEventDto,
  type LifecycleHookPresetDto,
} from '../api';
import {
  DEFAULT_LIFECYCLE_HOOK_EVENTS,
  EMPTY_HOOKS_CONFIG,
  EMPTY_HOOKS_JSON,
  type LifecycleHooksConfig,
  type LifecycleHookEventMeta,
  safeParseHooksJson,
  stringifyHooks,
} from '../constants/lifecycleHooks';

export type LifecycleHooksMode = 'preset' | 'form' | 'json';

export interface UseLifecycleHooksReturn {
  /** The canonical JSON string — always what gets persisted to the backend. */
  rawJson: string;
  /** Current UI editing mode. */
  mode: LifecycleHooksMode;
  /** Validated parsed config (null when JSON is invalid). */
  parsed: LifecycleHooksConfig | null;
  /** Human-readable validation errors; empty array means valid. */
  errors: string[];
  /** Events metadata (from API or fallback). */
  events: LifecycleHookEventMeta[];
  /** Presets metadata (from API; empty array while loading / on error). */
  presets: LifecycleHookPresetDto[];
  /** Loading flags. */
  isEventsLoading: boolean;
  isPresetsLoading: boolean;

  setRawJson: (next: string) => void;
  setMode: (mode: LifecycleHooksMode) => void;
  /** Replace the entire config (e.g. preset apply) — serializes to rawJson. */
  setConfig: (config: LifecycleHooksConfig) => void;
  /** Reset to empty config. */
  reset: () => void;
}

/**
 * Hook for managing lifecycle hook config state.
 *
 * The hook consumes `initialJson` only once, in the useState initializer. To
 * load a different agent's hooks, remount the consuming component (the editor
 * host is keyed on the current agent id so opening a different agent rebuilds
 * the state tree from scratch).
 *
 * @param initialJson — raw JSON string loaded from AgentEntity.lifecycleHooks
 *                     (null / empty → default empty config)
 */
export function useLifecycleHooks(initialJson: string | null | undefined): UseLifecycleHooksReturn {
  const [rawJson, setRawJson] = useState<string>(() => normalizeInitial(initialJson));
  const [mode, setMode] = useState<LifecycleHooksMode>('form');

  const { data: eventsData, isLoading: isEventsLoading } = useQuery({
    queryKey: ['lifecycle-hook-events'],
    queryFn: () => getLifecycleHookEvents().then((r) => r.data),
    staleTime: 86_400_000, // 1 day
    retry: 1,
  });

  const { data: presetsData, isLoading: isPresetsLoading } = useQuery({
    queryKey: ['lifecycle-hook-presets'],
    queryFn: () => getLifecycleHookPresets().then((r) => r.data),
    staleTime: 86_400_000,
    retry: 1,
  });

  const events = useMemo<LifecycleHookEventMeta[]>(() => {
    if (!eventsData || eventsData.length === 0) return DEFAULT_LIFECYCLE_HOOK_EVENTS;
    return mergeEventsWithFallback(eventsData, DEFAULT_LIFECYCLE_HOOK_EVENTS);
  }, [eventsData]);

  const presets = presetsData ?? [];

  const { parsed, errors } = useMemo(() => {
    const { parsed: p, errors: e } = safeParseHooksJson(rawJson);
    return { parsed: p, errors: e };
  }, [rawJson]);

  const setConfig = useCallback((config: LifecycleHooksConfig) => {
    setRawJson(stringifyHooks(config));
  }, []);

  const reset = useCallback(() => {
    setRawJson(stringifyHooks(EMPTY_HOOKS_CONFIG));
  }, []);

  return {
    rawJson,
    mode,
    parsed,
    errors,
    events,
    presets,
    isEventsLoading,
    isPresetsLoading,
    setRawJson,
    setMode,
    setConfig,
    reset,
  };
}

/** Pretty-print initial JSON if valid, fall back to raw / empty config. */
function normalizeInitial(initialJson: string | null | undefined): string {
  if (!initialJson) return EMPTY_HOOKS_JSON;
  try {
    const obj = JSON.parse(initialJson) as unknown;
    return JSON.stringify(obj, null, 2);
  } catch {
    return initialJson; // preserve bad JSON so the user can fix it in JSON mode
  }
}

/**
 * Merge API events with fallback metadata. API wins, but we keep the canonical
 * ordering + fill missing tooltip text with fallbacks so UI never shows blanks.
 */
function mergeEventsWithFallback(
  api: LifecycleHookEventDto[],
  fallback: LifecycleHookEventMeta[],
): LifecycleHookEventMeta[] {
  const byId = new Map(api.map((e) => [e.id, e]));
  return fallback.map((fb) => {
    const apiEvent = byId.get(fb.id);
    if (!apiEvent) return fb;
    return {
      id: fb.id, // narrow to canonical id type
      displayName: apiEvent.displayName || fb.displayName,
      description: apiEvent.description || fb.description,
      inputSchema:
        apiEvent.inputSchema && Object.keys(apiEvent.inputSchema).length > 0
          ? apiEvent.inputSchema
          : fb.inputSchema,
      canAbort: typeof apiEvent.canAbort === 'boolean' ? apiEvent.canAbort : fb.canAbort,
    };
  });
}
