/**
 * SYSTEM-AGENT-TYPING Phase 2.2 — tiny hook to persist a boolean flag in
 * localStorage. Used by AgentList for the "Show system agents" toggle so
 * the preference survives reload + new tabs.
 *
 * Why not a full settings context: only one consumer today; keep dependencies
 * lean and avoid the cost of a global provider. Promote to a context when a
 * second consumer appears.
 */
import { useCallback, useState } from 'react';

export function useLocalStorageBoolean(
  key: string,
  defaultValue: boolean,
): [boolean, (next: boolean) => void] {
  const [value, setValueState] = useState<boolean>(() => {
    if (typeof window === 'undefined') return defaultValue;
    try {
      const raw = window.localStorage.getItem(key);
      if (raw === null) return defaultValue;
      // Accept either JSON `true|false` or legacy `"true"|"false"` for forward
      // compatibility — both stringify the same way going forward.
      return raw === 'true' || raw === '"true"';
    } catch {
      // Safari private mode / locked-down storage — fall back to in-memory.
      return defaultValue;
    }
  });

  const setValue = useCallback(
    (next: boolean) => {
      setValueState(next);
      try {
        window.localStorage.setItem(key, String(next));
      } catch {
        // ignore storage write failures — UI state is still correct
      }
    },
    [key],
  );

  return [value, setValue];
}
