import { describe, expect, it } from 'vitest';
import {
  EMPTY_HOOKS_JSON,
  TIMEOUT_DEFAULT_SECONDS,
  buildDefaultEntry,
  buildDefaultHandler,
  countHookEntries,
  hookEntrySchema,
  migrateLegacyFlat,
  safeParseHooksJson,
  stringifyHooks,
  type LifecycleHooksConfig,
} from '../lifecycleHooks';

describe('safeParseHooksJson', () => {
  it('round-trips a config with all three handler kinds', () => {
    const cfg: LifecycleHooksConfig = {
      version: 1,
      hooks: {
        SessionStart: [
          {
            handler: { type: 'skill', skillName: 'greeter' },
            timeoutSeconds: 30,
            failurePolicy: 'CONTINUE',
            async: false,
          },
        ],
        UserPromptSubmit: [],
        PostToolUse: [
          {
            handler: { type: 'method', methodRef: 'log.file', args: { path: '/tmp/a' } },
            timeoutSeconds: 30,
            failurePolicy: 'CONTINUE',
            async: false,
          },
        ],
        Stop: [
          {
            handler: { type: 'script', scriptLang: 'bash', scriptBody: 'echo hi' },
            timeoutSeconds: 30,
            failurePolicy: 'CONTINUE',
            async: false,
          },
        ],
        SessionEnd: [],
      },
    };
    const raw = stringifyHooks(cfg);
    const { parsed, errors } = safeParseHooksJson(raw);
    expect(errors).toEqual([]);
    expect(parsed).not.toBeNull();
    expect(parsed?.hooks.SessionStart[0].handler).toEqual({
      type: 'skill',
      skillName: 'greeter',
    });
    expect(parsed?.hooks.Stop[0].handler).toMatchObject({
      type: 'script',
      scriptLang: 'bash',
      scriptBody: 'echo hi',
    });
    expect(parsed?.hooks.PostToolUse[0].handler).toMatchObject({
      type: 'method',
      methodRef: 'log.file',
    });
  });

  it('fills in defaults for minimal legal input and tolerates partial hooks map', () => {
    const minimal = {
      version: 1,
      hooks: {
        SessionStart: [{ handler: { type: 'skill', skillName: 'x' } }],
      },
    };
    const { parsed, errors } = safeParseHooksJson(JSON.stringify(minimal));
    expect(errors).toEqual([]);
    expect(parsed).not.toBeNull();
    const entry = parsed!.hooks.SessionStart[0];
    expect(entry.timeoutSeconds).toBe(TIMEOUT_DEFAULT_SECONDS);
    expect(entry.failurePolicy).toBe('CONTINUE');
    expect(entry.async).toBe(false);
    // Missing event buckets backfilled to [].
    expect(parsed!.hooks.PostToolUse).toEqual([]);
    expect(parsed!.hooks.SessionEnd).toEqual([]);
  });
});

describe('hookEntrySchema.refine', () => {
  it('rejects async + SKIP_CHAIN', () => {
    const result = hookEntrySchema.safeParse({
      handler: { type: 'skill', skillName: 'x' },
      timeoutSeconds: 30,
      failurePolicy: 'SKIP_CHAIN',
      async: true,
    });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues[0].message).toMatch(/SKIP_CHAIN/);
    }
  });
});

describe('buildDefaultHandler / buildDefaultEntry', () => {
  it('returns correct defaults per handler type', () => {
    expect(buildDefaultHandler('skill')).toEqual({ type: 'skill', skillName: '' });
    expect(buildDefaultHandler('script')).toEqual({
      type: 'script',
      scriptLang: 'bash',
      scriptBody: '',
    });
    expect(buildDefaultHandler('method')).toEqual({
      type: 'method',
      methodRef: '',
      args: {},
    });
  });

  it('buildDefaultEntry uses skill handler + canonical defaults + ui _id', () => {
    const entry = buildDefaultEntry();
    expect(entry.handler).toEqual({ type: 'skill', skillName: '' });
    expect(entry.timeoutSeconds).toBe(TIMEOUT_DEFAULT_SECONDS);
    expect(entry.failurePolicy).toBe('CONTINUE');
    expect(entry.async).toBe(false);
    expect(typeof entry._id).toBe('string');
    expect(entry._id!.length).toBeGreaterThan(0);
  });
});

describe('countHookEntries', () => {
  it('returns 0 for null / empty / legacy / bad JSON', () => {
    expect(countHookEntries(null)).toBe(0);
    expect(countHookEntries('')).toBe(0);
    expect(countHookEntries('not-json')).toBe(0);
    // Legacy flat shape isn't canonical, safeParse fails → 0.
    expect(countHookEntries('[{"event":"SessionStart","name":"x","type":"skill"}]')).toBe(0);
  });

  it('sums entries across events in canonical JSON', () => {
    const cfg: LifecycleHooksConfig = {
      version: 1,
      hooks: {
        SessionStart: [
          {
            handler: { type: 'skill', skillName: 'a' },
            timeoutSeconds: 30,
            failurePolicy: 'CONTINUE',
            async: false,
          },
        ],
        UserPromptSubmit: [],
        PostToolUse: [
          {
            handler: { type: 'skill', skillName: 'b' },
            timeoutSeconds: 30,
            failurePolicy: 'CONTINUE',
            async: false,
          },
          {
            handler: { type: 'method', methodRef: 'log.file', args: {} },
            timeoutSeconds: 30,
            failurePolicy: 'CONTINUE',
            async: false,
          },
        ],
        Stop: [],
        SessionEnd: [],
      },
    };
    expect(countHookEntries(stringifyHooks(cfg))).toBe(3);
  });
});

describe('migrateLegacyFlat', () => {
  it('returns EMPTY_HOOKS_JSON for null / empty input', () => {
    expect(migrateLegacyFlat(null)).toEqual({
      json: EMPTY_HOOKS_JSON,
      migratedCount: 0,
      droppedCount: 0,
      reasons: [],
    });
    expect(migrateLegacyFlat('')).toEqual({
      json: EMPTY_HOOKS_JSON,
      migratedCount: 0,
      droppedCount: 0,
      reasons: [],
    });
  });

  it('returns raw JSON unchanged on unparseable input', () => {
    const result = migrateLegacyFlat('this is not json');
    expect(result.json).toBe('this is not json');
    expect(result.migratedCount).toBe(0);
    expect(result.droppedCount).toBe(0);
  });

  it('re-stringifies already-canonical shape with no counts', () => {
    const canonical = {
      version: 1,
      hooks: {
        SessionStart: [
          {
            handler: { type: 'skill', skillName: 'x' },
            timeoutSeconds: 30,
            failurePolicy: 'CONTINUE',
            async: false,
          },
        ],
      },
    };
    const result = migrateLegacyFlat(JSON.stringify(canonical));
    expect(result.migratedCount).toBe(0);
    expect(result.droppedCount).toBe(0);
    const { parsed } = safeParseHooksJson(result.json);
    expect(parsed?.hooks.SessionStart[0].handler).toEqual({
      type: 'skill',
      skillName: 'x',
    });
  });

  it('migrates legacy flat skill entry', () => {
    const legacy = JSON.stringify([
      { event: 'SessionStart', name: 'foo', type: 'skill' },
    ]);
    const result = migrateLegacyFlat(legacy);
    expect(result.migratedCount).toBe(1);
    expect(result.droppedCount).toBe(0);
    const { parsed, errors } = safeParseHooksJson(result.json);
    expect(errors).toEqual([]);
    expect(parsed?.hooks.SessionStart[0].handler).toEqual({
      type: 'skill',
      skillName: 'foo',
    });
  });

  it('migrates legacy flat method entry', () => {
    const legacy = JSON.stringify([
      { event: 'SessionStart', name: 'log.file', type: 'method' },
    ]);
    const result = migrateLegacyFlat(legacy);
    expect(result.migratedCount).toBe(1);
    expect(result.droppedCount).toBe(0);
    const { parsed } = safeParseHooksJson(result.json);
    expect(parsed?.hooks.SessionStart[0].handler).toEqual({
      type: 'method',
      methodRef: 'log.file',
      args: {},
    });
  });

  it('drops legacy script entries without scriptBody', () => {
    const legacy = JSON.stringify([
      { event: 'SessionStart', name: 'runme', type: 'script' },
    ]);
    const result = migrateLegacyFlat(legacy);
    expect(result.migratedCount).toBe(0);
    expect(result.droppedCount).toBe(1);
    expect(result.reasons[0]).toMatch(/no scriptBody/);
  });

  it('drops legacy entries with unknown event ids', () => {
    const legacy = JSON.stringify([
      { event: 'PreToolUse', name: 'x', type: 'skill' },
    ]);
    const result = migrateLegacyFlat(legacy);
    expect(result.migratedCount).toBe(0);
    expect(result.droppedCount).toBe(1);
    expect(result.reasons[0]).toMatch(/unknown event/);
  });

  it('drops legacy entries with unsupported type "command"', () => {
    const legacy = JSON.stringify([
      { event: 'SessionStart', name: 'bash', type: 'command' },
    ]);
    const result = migrateLegacyFlat(legacy);
    expect(result.migratedCount).toBe(0);
    expect(result.droppedCount).toBe(1);
    expect(result.reasons[0]).toMatch(/unsupported type/);
  });

  it('handles a mixed batch (3 migratable + 1 unknown event + 1 script)', () => {
    const legacy = JSON.stringify([
      { event: 'SessionStart', name: 's1', type: 'skill' },
      { event: 'PostToolUse', name: 'log.file', type: 'method' },
      { event: 'SessionEnd', name: 's3', type: 'skill' },
      { event: 'PreToolUse', name: 'unknown-evt', type: 'skill' },
      { event: 'SessionStart', name: 'scriptHere', type: 'script' },
    ]);
    const result = migrateLegacyFlat(legacy);
    expect(result.migratedCount).toBe(3);
    expect(result.droppedCount).toBe(2);
    const { parsed } = safeParseHooksJson(result.json);
    expect(parsed?.hooks.SessionStart.length).toBe(1);
    expect(parsed?.hooks.PostToolUse.length).toBe(1);
    expect(parsed?.hooks.SessionEnd.length).toBe(1);
  });

  it('unwraps {hooks: [...]} legacy shape too', () => {
    const legacy = JSON.stringify({
      hooks: [{ event: 'SessionStart', name: 'foo', type: 'skill' }],
    });
    const result = migrateLegacyFlat(legacy);
    expect(result.migratedCount).toBe(1);
  });

  it('drops unknown event keys from canonical-shaped payloads instead of silently stripping', () => {
    const payload = JSON.stringify({
      version: 1,
      hooks: {
        PreToolUse: [{ handler: { type: 'skill', skillName: 'x' } }],
      },
    });
    const result = migrateLegacyFlat(payload);
    expect(result.droppedCount).toBe(1);
    expect(result.reasons[0]).toMatch(/unknown event "PreToolUse"/);
    const { parsed } = safeParseHooksJson(result.json);
    expect(parsed).not.toBeNull();
    // Known event slots remain, unknown keys absent.
    expect(parsed?.hooks.SessionStart).toEqual([]);
  });

  it('preserves known-event entries alongside dropping unknown-event entries', () => {
    const payload = JSON.stringify({
      version: 1,
      hooks: {
        SessionStart: [
          {
            handler: { type: 'skill', skillName: 'ok' },
            timeoutSeconds: 30,
            failurePolicy: 'CONTINUE',
            async: false,
          },
        ],
        PreToolUse: [
          { handler: { type: 'skill', skillName: 'a' } },
          { handler: { type: 'skill', skillName: 'b' } },
        ],
      },
    });
    const result = migrateLegacyFlat(payload);
    expect(result.droppedCount).toBe(2);
    expect(result.reasons[0]).toMatch(/unknown event "PreToolUse" \(2 entries\)/);
    const { parsed } = safeParseHooksJson(result.json);
    expect(parsed?.hooks.SessionStart[0].handler).toEqual({
      type: 'skill',
      skillName: 'ok',
    });
  });

  it('migrated entries inherit defaults from buildDefaultEntry factory (single source)', () => {
    const legacy = JSON.stringify([
      { event: 'SessionStart', name: 'foo', type: 'skill' },
    ]);
    const { json } = migrateLegacyFlat(legacy);
    const { parsed } = safeParseHooksJson(json);
    const migratedEntry = parsed!.hooks.SessionStart[0];
    const factoryDefaults = buildDefaultEntry();
    expect(migratedEntry.timeoutSeconds).toBe(factoryDefaults.timeoutSeconds);
    expect(migratedEntry.failurePolicy).toBe(factoryDefaults.failurePolicy);
    expect(migratedEntry.async).toBe(factoryDefaults.async);
  });
});
