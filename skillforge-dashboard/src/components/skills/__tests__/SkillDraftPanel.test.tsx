/**
 * P1-C-8 FE: similarity badge thresholds (≥0.85 high / 0.60-0.85 suggest / <0.60 hidden).
 * Asserts the tri-state rendering driven by SkillDraft.similarity + mergeCandidate.
 */
import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { SkillDraftsSection } from '../SkillDraftPanel';
import type { SkillDraft } from '../../../api';

function makeDraft(overrides: Partial<SkillDraft> = {}): SkillDraft {
  return {
    id: 'd1',
    ownerId: 1,
    name: 'sample-skill',
    status: 'draft',
    createdAt: new Date().toISOString(),
    ...overrides,
  };
}

describe('SkillDraftPanel — similarity badge', () => {
  it('renders red "High similarity" badge when similarity >= 0.85', () => {
    const draft = makeDraft({ id: 'high', similarity: 0.92, mergeCandidateName: 'existing-skill' });
    render(
      <SkillDraftsSection
        drafts={[draft]}
        pendingCount={1}
        onClose={vi.fn()}
        onApprove={vi.fn()}
        onDiscard={vi.fn()}
        approvingId={null}
        discardingId={null}
      />,
    );
    const badge = screen.getByTestId('similarity-badge');
    expect(badge).toBeInTheDocument();
    expect(badge.textContent).toMatch(/High similarity 92%/);
    expect(badge.textContent).toMatch(/existing-skill/);
  });

  it('renders amber "Suggest merge" badge when 0.60 <= similarity < 0.85', () => {
    const draft = makeDraft({ id: 'mid', similarity: 0.72 });
    render(
      <SkillDraftsSection
        drafts={[draft]}
        pendingCount={1}
        onClose={vi.fn()}
        onApprove={vi.fn()}
        onDiscard={vi.fn()}
        approvingId={null}
        discardingId={null}
      />,
    );
    const badge = screen.getByTestId('similarity-badge');
    expect(badge).toBeInTheDocument();
    expect(badge.textContent).toMatch(/Suggest merge 72%/);
  });

  it('renders no badge when similarity < 0.60', () => {
    const draft = makeDraft({ id: 'low', similarity: 0.5 });
    render(
      <SkillDraftsSection
        drafts={[draft]}
        pendingCount={1}
        onClose={vi.fn()}
        onApprove={vi.fn()}
        onDiscard={vi.fn()}
        approvingId={null}
        discardingId={null}
      />,
    );
    expect(screen.queryByTestId('similarity-badge')).toBeNull();
  });

  it('renders no badge when similarity is undefined', () => {
    const draft = makeDraft({ id: 'undef' });
    render(
      <SkillDraftsSection
        drafts={[draft]}
        pendingCount={1}
        onClose={vi.fn()}
        onApprove={vi.fn()}
        onDiscard={vi.fn()}
        approvingId={null}
        discardingId={null}
      />,
    );
    expect(screen.queryByTestId('similarity-badge')).toBeNull();
  });

  it('badge boundary: similarity exactly 0.85 → high badge', () => {
    const draft = makeDraft({ id: 'edge', similarity: 0.85 });
    render(
      <SkillDraftsSection
        drafts={[draft]}
        pendingCount={1}
        onClose={vi.fn()}
        onApprove={vi.fn()}
        onDiscard={vi.fn()}
        approvingId={null}
        discardingId={null}
      />,
    );
    expect(screen.getByTestId('similarity-badge').textContent).toMatch(/High similarity/);
  });
});
