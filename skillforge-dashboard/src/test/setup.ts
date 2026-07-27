import '@testing-library/jest-dom';

/**
 * Node 26 exposes an experimental global `localStorage` accessor that returns
 * `undefined` unless the process receives `--localstorage-file`. That accessor can
 * shadow jsdom's in-memory Storage implementation inside Vitest workers.
 *
 * Tests must be hermetic and must never persist auth/theme state to a host file, so
 * install a small spec-compatible in-memory Storage for every worker.
 */
class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>();

  get length(): number {
    return this.values.size;
  }

  clear(): void {
    this.values.clear();
  }

  getItem(key: string): string | null {
    return this.values.get(String(key)) ?? null;
  }

  key(index: number): string | null {
    return Array.from(this.values.keys())[index] ?? null;
  }

  removeItem(key: string): void {
    this.values.delete(String(key));
  }

  setItem(key: string, value: string): void {
    this.values.set(String(key), String(value));
  }
}

const localStorageForTests = new MemoryStorage();
Object.defineProperty(globalThis, 'localStorage', {
  configurable: true,
  value: localStorageForTests,
});
Object.defineProperty(window, 'localStorage', {
  configurable: true,
  value: localStorageForTests,
});
