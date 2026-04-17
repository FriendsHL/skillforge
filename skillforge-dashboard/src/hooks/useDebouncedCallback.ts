import { useCallback, useEffect, useRef } from 'react';

/**
 * Debounce a callback. Rapid calls coalesce into a single deferred invocation
 * with the latest arguments. The cleanup on unmount cancels any pending timer
 * so debounced callbacks cannot fire after the component is gone.
 *
 * `flush` synchronously invokes the pending call (if any) and clears the timer.
 * Use it in blur / submit paths so downstream reads see the latest value.
 */
export function useDebouncedCallback<Args extends unknown[]>(
  fn: (...args: Args) => void,
  delay: number,
): readonly [debounced: (...args: Args) => void, flush: () => void] {
  const fnRef = useRef(fn);
  fnRef.current = fn;

  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pendingArgsRef = useRef<Args | null>(null);

  useEffect(
    () => () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    },
    [],
  );

  const debounced = useCallback(
    (...args: Args) => {
      pendingArgsRef.current = args;
      if (timerRef.current) clearTimeout(timerRef.current);
      timerRef.current = setTimeout(() => {
        timerRef.current = null;
        const pending = pendingArgsRef.current;
        if (pending) {
          pendingArgsRef.current = null;
          fnRef.current(...pending);
        }
      }, delay);
    },
    [delay],
  );

  const flush = useCallback(() => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    const pending = pendingArgsRef.current;
    if (pending) {
      pendingArgsRef.current = null;
      fnRef.current(...pending);
    }
  }, []);

  return [debounced, flush] as const;
}
