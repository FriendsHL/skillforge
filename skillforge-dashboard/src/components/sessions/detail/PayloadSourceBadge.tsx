import React from 'react';
import type { BlobStatus } from '../../../types/observability';

interface PayloadSourceBadgeProps {
  /** Backend blob_status. null → unknown (treat as live, no badge). */
  blobStatus: BlobStatus | null | undefined;
}

const VARIANT: Record<BlobStatus, { label: string; tone: 'ok' | 'muted' | 'warn' | 'error' }> = {
  ok: { label: 'live', tone: 'ok' },
  legacy: { label: 'legacy', tone: 'muted' },
  truncated: { label: 'truncated', tone: 'warn' },
  write_failed: { label: 'write failed', tone: 'error' },
};

/**
 * R2-W6 + R3: surface the four blob_status states distinctly so operators
 * can tell live writes from historical legacy data and from write-failed live
 * writes. Used inside PayloadViewer header.
 */
const PayloadSourceBadge: React.FC<PayloadSourceBadgeProps> = ({ blobStatus }) => {
  if (!blobStatus) return null;
  const v = VARIANT[blobStatus];
  if (!v) return null;
  return (
    <span
      className={`obs-blob-badge obs-blob-badge--${v.tone}`}
      data-status={blobStatus}
      aria-label={`Blob status: ${v.label}`}
    >
      {v.label}
    </span>
  );
};

export default PayloadSourceBadge;
