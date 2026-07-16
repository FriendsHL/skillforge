import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import RuntimeBanner from '../RuntimeBanner';

describe('RuntimeBanner retry action', () => {
  it('offers retry for a failed turn', () => {
    const onRetry = vi.fn();

    render(
      <RuntimeBanner
        runtimeStatus="error"
        runtimeStep="retryable"
        runtimeError="Provider unavailable"
        cancelling={false}
        retrying={false}
        onCancel={vi.fn()}
        onRetry={onRetry}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Retry failed turn' }));

    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('disables duplicate retry while the request is being accepted', () => {
    render(
      <RuntimeBanner
        runtimeStatus="error"
        runtimeStep="retryable"
        runtimeError="Provider unavailable"
        cancelling={false}
        retrying
        onCancel={vi.fn()}
        onRetry={vi.fn()}
      />,
    );

    expect(screen.getByRole('button', { name: 'Retrying failed turn' })).toBeDisabled();
  });
});
