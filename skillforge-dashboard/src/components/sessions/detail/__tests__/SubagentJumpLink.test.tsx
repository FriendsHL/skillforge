/**
 * OBS-1 R3-WN2 — SubagentJumpLink: renders a clickable jump link that calls
 * `navigate('/sessions/<targetSessionId>')` when the user clicks. The link
 * lives ONLY on Tool span detail; this test exercises the leaf component.
 */
import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import SubagentJumpLink from '../SubagentJumpLink';

void React;

function LocationProbe() {
  const loc = useLocation();
  return <div data-testid="loc">{loc.pathname}</div>;
}

describe('SubagentJumpLink', () => {
  it('navigates to the target session on click', () => {
    const target = '11111111-2222-3333-4444-555555555555';
    render(
      <MemoryRouter initialEntries={['/sessions/parent-session']}>
        <Routes>
          <Route
            path="/sessions/:id"
            element={
              <>
                <SubagentJumpLink targetSessionId={target} />
                <LocationProbe />
              </>
            }
          />
        </Routes>
      </MemoryRouter>,
    );

    expect(screen.getByText(/子 session/)).toBeInTheDocument();
    expect(screen.getByText(/\[11111111\]/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /Jump to child session/ }));
    expect(screen.getByTestId('loc').textContent).toBe(`/sessions/${target}`);
  });
});
