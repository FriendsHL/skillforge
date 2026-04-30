/**
 * P1-D T9 — SkillTable governance UI: artifactStatus badges, system tag,
 * shadowedBy tooltip, lastScannedAt column. The table no longer expresses
 * the binary enabled/disabled status as the primary signal; artifactStatus
 * is the new top-level state, with a secondary `disabled` chip when needed.
 */
import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SkillTable } from '../SkillTable';
import type { SkillRow } from '../types';

if (!window.matchMedia) {
  window.matchMedia = (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  });
}

function makeRow(overrides: Partial<SkillRow>): SkillRow {
  return {
    id: 1,
    name: 'sample-skill',
    description: 'desc',
    source: 'custom',
    lang: 'md',
    enabled: true,
    system: false,
    isSystem: false,
    artifactStatus: 'active',
    type: 'runtime',
    tags: [],
    ...overrides,
  };
}

describe('SkillTable — P1-D governance fields', () => {
  it('renders the system tag for isSystem rows', () => {
    const row = makeRow({ id: 's-1', isSystem: true, system: true, source: 'system', type: 'system' });
    render(<SkillTable rows={[row]} onOpenDetail={() => {}} />);
    const tag = screen.getByTestId('system-tag');
    expect(tag).toBeInTheDocument();
    expect(tag.textContent?.toLowerCase()).toContain('system');
  });

  it('renders an artifact status badge for non-active states', () => {
    const rows: SkillRow[] = [
      makeRow({ id: 'a', artifactStatus: 'missing', name: 'gone' }),
      makeRow({ id: 'b', artifactStatus: 'invalid', name: 'broken' }),
      makeRow({
        id: 'c',
        artifactStatus: 'shadowed',
        name: 'shadowed-one',
        shadowedBy: 'winning-peer',
      }),
    ];
    render(<SkillTable rows={rows} onOpenDetail={() => {}} />);
    expect(screen.getByText('missing')).toBeInTheDocument();
    expect(screen.getByText('invalid')).toBeInTheDocument();
    expect(screen.getByText('shadowed')).toBeInTheDocument();
  });

  it('shows the originSource string in the Source column when present', () => {
    const row = makeRow({ originSource: 'skill-creator' });
    render(<SkillTable rows={[row]} onOpenDetail={() => {}} />);
    expect(screen.getByText('skill-creator')).toBeInTheDocument();
  });

  it('falls back to the system/custom UI category when originSource is missing', () => {
    const row = makeRow({ originSource: undefined, source: 'custom' });
    render(<SkillTable rows={[row]} onOpenDetail={() => {}} />);
    expect(screen.getByText('custom')).toBeInTheDocument();
  });

  it('renders Last scanned column for rows with lastScannedAt', () => {
    const recent = new Date(Date.now() - 5 * 60_000).toISOString(); // 5 min ago
    const row = makeRow({ lastScannedAt: recent });
    render(<SkillTable rows={[row]} onOpenDetail={() => {}} />);
    // 5m ago heuristic from timeAgo()
    expect(screen.getByText(/m ago/)).toBeInTheDocument();
  });

  it('still renders the secondary disabled chip when enabled=false', () => {
    const row = makeRow({ enabled: false });
    render(<SkillTable rows={[row]} onOpenDetail={() => {}} />);
    expect(screen.getByText('disabled')).toBeInTheDocument();
  });
});
