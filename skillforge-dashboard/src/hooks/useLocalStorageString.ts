/**
 * SYSTEM-AGENT-TYPING Phase 2 UX refactor — tiny hook to persist a string
 * value in localStorage. Used by AgentList / SessionList / ChatSidebar for
 * the "User Agents" / "System Agents" Tabs so the active tab survives reload
 * + new tabs. Sibling to useLocalStorageBoolean.
 *
 * `allowedValues` (optional) narrows the persisted value to a known set —
 * if localStorage holds garbage (manually edited, schema drift, etc.) we
 * fall back to defaultValue instead of trusting it. This guards the Tabs
 * activeKey, which AntD treats opaquely (an unknown key just renders
 * nothing).
 */
import { useCallback, useState } from 'react';

export function useLocalStorageString<T extends string>(
  key: string,
  defaultValue: T,
  allowedValues?: readonly T[],
): [T, (next: T) => void] {
  const [value, setValueState] = useState<T>(() => {
    if (typeof window === 'undefined') return defaultValue;
    try {
      const raw = window.localStorage.getItem(key);
      if (raw === null) return defaultValue;
      if (allowedValues && !allowedValues.includes(raw as T)) return defaultValue;
      return raw as T;
    } catch {
      // Safari private mode / locked-down storage — fall back to in-memory.
      return defaultValue;
    }
  });

  const setValue = useCallback(
    (next: T) => {
      setValueState(next);
      try {
        window.localStorage.setItem(key, next);
      } catch {
        // ignore storage write failures — UI state is still correct
      }
    },
    [key],
  );

  return [value, setValue];
}
