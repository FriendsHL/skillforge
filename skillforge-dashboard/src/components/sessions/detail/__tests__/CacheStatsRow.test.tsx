/**
 * CacheStatsRow — visual contract test (PROMPT-CACHE-MVP Phase 4 FE).
 *
 * Asserts the rendering rules documented in {@link ../CacheStatsRow.tsx}:
 *   1. Always renders the cache numbers row (read / write / prompt) with
 *      thousands separators and a 0 fallback for null fields. The "prompt"
 *      number is the SUM of the three buckets (Anthropic semantics) — the
 *      caller passes only the non-cached input.
 *   2. Hit-rate badge present only when `cacheReadTokens > 0 &&
 *      promptTokens > 0`. Colour reflects threshold:
 *        >= 50%   → green   (`ant-tag-green`)
 *        20%-50%  → gold    (`ant-tag-gold`)
 *        > 0%-20% → default (no semantic suffix)
 *   3. Cache break red badge renders iff `metadata.cache_break === true`.
 *
 * r2 (2026-05-08): denominator changed from "non-cached input only" to
 * "sum of all three buckets" per r1 review B1; tests recalibrated so that
 * the threshold scenarios still hit the intended colour band.
 */
import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import CacheStatsRow, { hitRateTagColor } from '../CacheStatsRow';

void React;

describe('CacheStatsRow — number row', () => {
  it('formats tokens with thousands separators and falls back to 0 for null', () => {
    render(
      <CacheStatsRow
        cacheReadTokens={null}
        cacheCreationTokens={null}
        nonCachedInputTokens={5123}
      />,
    );
    const row = screen.getByTestId('cache-stats-row');
    expect(row.textContent).toContain('read 0');
    expect(row.textContent).toContain('write 0');
    // prompt = nonCached + read + write = 5123 + 0 + 0 = 5123
    expect(row.textContent).toContain('prompt 5,123');
  });

  it('sums the three buckets for the prompt total (Anthropic semantics)', () => {
    render(
      <CacheStatsRow
        cacheReadTokens={80000}
        cacheCreationTokens={2000}
        nonCachedInputTokens={500}
      />,
    );
    const row = screen.getByTestId('cache-stats-row');
    expect(row.textContent).toContain('read 80,000');
    expect(row.textContent).toContain('write 2,000');
    // prompt = 500 + 80000 + 2000 = 82,500 (the high-cache scenario from r1 B1)
    expect(row.textContent).toContain('prompt 82,500');
  });

  it('formats large numbers with thousands separators', () => {
    render(
      <CacheStatsRow
        cacheReadTokens={1234567}
        cacheCreationTokens={2048}
        nonCachedInputTokens={263385}
      />,
    );
    const row = screen.getByTestId('cache-stats-row');
    expect(row.textContent).toContain('read 1,234,567');
    expect(row.textContent).toContain('write 2,048');
    // 263385 + 1234567 + 2048 = 1,500,000
    expect(row.textContent).toContain('prompt 1,500,000');
  });
});

describe('CacheStatsRow — hit rate badge', () => {
  it('hides the badge entirely when cacheRead is 0 (avoids misleading 0.0%)', () => {
    render(
      <CacheStatsRow
        cacheReadTokens={0}
        cacheCreationTokens={500}
        nonCachedInputTokens={10000}
      />,
    );
    expect(screen.queryByTestId('cache-hit-rate-row')).toBeNull();
  });

  it('hides the badge entirely when all three buckets are 0 (avoids divide-by-zero)', () => {
    render(
      <CacheStatsRow
        cacheReadTokens={0}
        cacheCreationTokens={0}
        nonCachedInputTokens={0}
      />,
    );
    expect(screen.queryByTestId('cache-hit-rate-row')).toBeNull();
  });

  it('renders a green badge above 50% with one-decimal hit rate', () => {
    const { container } = render(
      // 8730 / (8730 + 770 + 500) = 8730/10000 = 87.3%
      <CacheStatsRow
        cacheReadTokens={8730}
        cacheCreationTokens={500}
        nonCachedInputTokens={770}
      />,
    );
    const row = screen.getByTestId('cache-hit-rate-row');
    expect(row.textContent).toContain('87.3%');
    const tag = container.querySelector('[data-testid="cache-hit-rate-row"] .ant-tag');
    expect(tag).toBeTruthy();
    expect(tag!.className).toMatch(/ant-tag-green/);
  });

  it('renders a green badge exactly at the 50% boundary (>= inclusive)', () => {
    const { container } = render(
      // 5000 / (5000 + 0 + 5000) = 50.0% → green
      <CacheStatsRow
        cacheReadTokens={5000}
        cacheCreationTokens={0}
        nonCachedInputTokens={5000}
      />,
    );
    const row = screen.getByTestId('cache-hit-rate-row');
    expect(row.textContent).toContain('50.0%');
    const tag = container.querySelector('[data-testid="cache-hit-rate-row"] .ant-tag');
    expect(tag!.className).toMatch(/ant-tag-green/);
  });

  it('renders a gold badge between 20% (inclusive) and 50% (exclusive)', () => {
    const { container } = render(
      // 3500 / (3500 + 1000 + 5500) = 3500/10000 = 35.0% → gold
      <CacheStatsRow
        cacheReadTokens={3500}
        cacheCreationTokens={1000}
        nonCachedInputTokens={5500}
      />,
    );
    const row = screen.getByTestId('cache-hit-rate-row');
    expect(row.textContent).toContain('35.0%');
    const tag = container.querySelector('[data-testid="cache-hit-rate-row"] .ant-tag');
    expect(tag!.className).toMatch(/ant-tag-gold/);
  });

  it('renders a gold badge exactly at the 20% boundary (>= inclusive)', () => {
    const { container } = render(
      // 2000 / (2000 + 0 + 8000) = 20.0% → gold
      <CacheStatsRow
        cacheReadTokens={2000}
        cacheCreationTokens={0}
        nonCachedInputTokens={8000}
      />,
    );
    const row = screen.getByTestId('cache-hit-rate-row');
    expect(row.textContent).toContain('20.0%');
    const tag = container.querySelector('[data-testid="cache-hit-rate-row"] .ant-tag');
    expect(tag!.className).toMatch(/ant-tag-gold/);
  });

  it('renders a default-grey badge below 20%', () => {
    const { container } = render(
      // 500 / (500 + 0 + 9500) = 5.0% → default-grey
      <CacheStatsRow
        cacheReadTokens={500}
        cacheCreationTokens={0}
        nonCachedInputTokens={9500}
      />,
    );
    const row = screen.getByTestId('cache-hit-rate-row');
    expect(row.textContent).toContain('5.0%');
    const tag = container.querySelector('[data-testid="cache-hit-rate-row"] .ant-tag');
    expect(tag).toBeTruthy();
    // Default antd Tag has no semantic colour suffix.
    expect(tag!.className).not.toMatch(/ant-tag-(green|red|blue|orange|gold|purple|geekblue)/);
  });

  it('does NOT exceed 100% on the high-cache scenario from r1 B1 (regression)', () => {
    const { container } = render(
      // r1 B1 broken example: cacheRead=80,000, nonCached=500.
      // Old behaviour: 80000/500 = 16,000% (always green, threshold meaningless).
      // New behaviour: 80000/(500+80000+0) = 99.4% (green, but < 100%).
      <CacheStatsRow
        cacheReadTokens={80000}
        cacheCreationTokens={0}
        nonCachedInputTokens={500}
      />,
    );
    const row = screen.getByTestId('cache-hit-rate-row');
    const text = row.textContent ?? '';
    expect(text).toMatch(/99\.4%/);
    // Critical assertion: rate must NOT exceed 100%.
    const match = text.match(/(\d+\.\d+)%/);
    expect(match).toBeTruthy();
    const pct = parseFloat(match![1]);
    expect(pct).toBeLessThanOrEqual(100);
    const tag = container.querySelector('[data-testid="cache-hit-rate-row"] .ant-tag');
    expect(tag!.className).toMatch(/ant-tag-green/);
  });
});

describe('CacheStatsRow — cache break badge', () => {
  it('does not render the break row when metadata.cache_break is absent', () => {
    render(
      <CacheStatsRow
        cacheReadTokens={5000}
        cacheCreationTokens={0}
        nonCachedInputTokens={5000}
        metadata={null}
      />,
    );
    expect(screen.queryByTestId('cache-break-row')).toBeNull();
  });

  it('does not render the break row when cache_break is false', () => {
    render(
      <CacheStatsRow
        cacheReadTokens={5000}
        cacheCreationTokens={0}
        nonCachedInputTokens={5000}
        metadata={{ cache_break: false }}
      />,
    );
    expect(screen.queryByTestId('cache-break-row')).toBeNull();
  });

  it('renders a red break badge when metadata.cache_break === true', () => {
    const { container } = render(
      <CacheStatsRow
        cacheReadTokens={500}
        cacheCreationTokens={0}
        nonCachedInputTokens={9500}
        metadata={{ cache_break: true, cache_break_reason: 'tool schema drifted' }}
      />,
    );
    const row = screen.getByTestId('cache-break-row');
    expect(row.textContent).toContain('cache broken at this turn');
    const tag = container.querySelector('[data-testid="cache-break-row"] .ant-tag');
    expect(tag).toBeTruthy();
    expect(tag!.className).toMatch(/ant-tag-red/);
  });

  it('renders the break badge even when cache_break_reason is missing', () => {
    const { container } = render(
      <CacheStatsRow
        cacheReadTokens={0}
        cacheCreationTokens={0}
        nonCachedInputTokens={10000}
        metadata={{ cache_break: true }}
      />,
    );
    const tag = container.querySelector('[data-testid="cache-break-row"] .ant-tag');
    expect(tag).toBeTruthy();
    expect(tag!.className).toMatch(/ant-tag-red/);
  });
});

describe('hitRateTagColor (pure helper)', () => {
  it('returns null for non-positive rates', () => {
    expect(hitRateTagColor(0)).toBeNull();
    expect(hitRateTagColor(-1)).toBeNull();
  });

  it('returns "green" at and above 50%', () => {
    expect(hitRateTagColor(50)).toBe('green');
    expect(hitRateTagColor(99.9)).toBe('green');
  });

  it('returns "gold" in [20, 50)', () => {
    expect(hitRateTagColor(20)).toBe('gold');
    expect(hitRateTagColor(49.999)).toBe('gold');
  });

  it('returns "default" in (0, 20)', () => {
    expect(hitRateTagColor(0.1)).toBe('default');
    expect(hitRateTagColor(19.999)).toBe('default');
  });
});
