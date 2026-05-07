/**
 * McpServerStatusTag — visual contract test.
 *
 * Asserts:
 *   - Each of the 3 status values renders the matching label
 *   - Antd Tag's color class reflects the colour map
 *     (`ant-tag-green` / default / `ant-tag-red`) — we don't snapshot the
 *     exact colour because the antd theme can swap the token between
 *     dark/light without changing semantic correctness.
 *   - `undefined` falls through to `'disconnected'` (so the table never
 *     has empty status cells).
 */
import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import McpServerStatusTag from '../McpServerStatusTag';

describe('McpServerStatusTag', () => {
  it('renders "connected" with green colour class', () => {
    const { container } = render(<McpServerStatusTag status="connected" />);
    expect(screen.getByText('connected')).toBeInTheDocument();
    const tag = container.querySelector('.ant-tag');
    expect(tag).toBeTruthy();
    expect(tag!.className).toMatch(/ant-tag-green/);
  });

  it('renders "disconnected" with default colour (no semantic colour class)', () => {
    const { container } = render(<McpServerStatusTag status="disconnected" />);
    expect(screen.getByText('disconnected')).toBeInTheDocument();
    const tag = container.querySelector('.ant-tag');
    expect(tag).toBeTruthy();
    // Default antd Tag has no semantic colour suffix like `ant-tag-green`.
    expect(tag!.className).not.toMatch(/ant-tag-(green|red|blue|orange|purple|geekblue)/);
  });

  it('renders "error" with red colour class', () => {
    const { container } = render(<McpServerStatusTag status="error" />);
    expect(screen.getByText('error')).toBeInTheDocument();
    const tag = container.querySelector('.ant-tag');
    expect(tag).toBeTruthy();
    expect(tag!.className).toMatch(/ant-tag-red/);
  });

  it('falls back to "disconnected" when status is undefined', () => {
    render(<McpServerStatusTag status={undefined} />);
    expect(screen.getByText('disconnected')).toBeInTheDocument();
  });
});
