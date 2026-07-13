import React, { useEffect, useState } from 'react';
import { Image as AntdImage, Tag, Tooltip } from 'antd';
import {
  FilePdfOutlined,
  FileWordOutlined,
  FileExcelOutlined,
  FileTextOutlined,
  LoadingOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { getChatAttachmentBlob } from '../api';

/**
 * MULTIMODAL-MVP Phase 2 / Wave 3: render an inline thumbnail (image) or chip
 * (PDF / Word / Excel / CSV) for an `image_ref` / `pdf_ref` / `word_ref` /
 * `excel_ref` / `csv_ref` content block in a chat message bubble.
 *
 * <p>Images are fetched via axios (Bearer auth interceptor) and rendered as a
 * blob URL inside an AntD `Image` (click → full-screen preview). Non-image
 * kinds render a compact chip; click opens the bytes in a new tab. Blob URLs
 * are revoked on unmount.</p>
 *
 * <p>Loading shows a small skeleton; fetch failure shows a fallback chip so
 * the user still knows what they uploaded even if the data endpoint is down.</p>
 */
interface AttachmentThumbnailProps {
  kind: 'image' | 'pdf' | 'word' | 'excel' | 'csv';
  attachmentId: string;
  filename: string;
  userId: number;
  sessionId?: string;
  /** PDF only — page count surfaced as a chip badge. */
  pageCount?: number;
  /** Excel only — sheet count surfaced as a chip badge. */
  sheetCount?: number;
  caption?: string;
}

type LoadState = 'loading' | 'loaded' | 'error';

/**
 * Wave 3 — visual metadata per non-image kind. Keep the table central so
 * adding a new kind only touches one place. AntD `Tag` color "default" keeps
 * the chip neutral — kind-specific tinting via icon color rather than full
 * background tint, matching the developer-precision style direction.
 */
const CHIP_META: Record<
  'pdf' | 'word' | 'excel' | 'csv',
  { icon: React.ReactNode; testId: string }
> = {
  pdf: {
    icon: <FilePdfOutlined style={{ fontSize: 14, color: 'var(--accent-error, #d4380d)' }} />,
    testId: 'attachment-pdf-chip',
  },
  word: {
    icon: <FileWordOutlined style={{ fontSize: 14, color: 'var(--accent-info, #2f54eb)' }} />,
    testId: 'attachment-word-chip',
  },
  excel: {
    icon: <FileExcelOutlined style={{ fontSize: 14, color: 'var(--accent-success, #389e0d)' }} />,
    testId: 'attachment-excel-chip',
  },
  csv: {
    icon: <FileTextOutlined style={{ fontSize: 14, color: 'var(--fg-3, #8a8a93)' }} />,
    testId: 'attachment-csv-chip',
  },
};

const AttachmentThumbnail: React.FC<AttachmentThumbnailProps> = ({
  kind,
  attachmentId,
  filename,
  userId,
  sessionId,
  pageCount,
  sheetCount,
  caption,
}) => {
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [loadState, setLoadState] = useState<LoadState>('loading');
  const [retryAttempt, setRetryAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    let createdUrl: string | null = null;
    setLoadState('loading');
    setBlobUrl(null);
    getChatAttachmentBlob(attachmentId, userId, sessionId)
      .then((res) => {
        if (cancelled) return;
        // axios with responseType:'blob' returns the Blob in res.data
        const url = URL.createObjectURL(res.data as unknown as Blob);
        createdUrl = url;
        setBlobUrl(url);
        setLoadState('loaded');
      })
      .catch(() => {
        if (cancelled) return;
        setLoadState('error');
      });
    return () => {
      cancelled = true;
      if (createdUrl) {
        URL.revokeObjectURL(createdUrl);
      }
    };
  }, [attachmentId, userId, sessionId, retryAttempt]);

  const retry = () => setRetryAttempt((attempt) => attempt + 1);
  const captionNode = caption ? (
    <span
      style={{
        color: 'var(--fg-3, #8a8a93)',
        fontSize: 11,
        lineHeight: 1.4,
        maxWidth: kind === 'image' ? 200 : 280,
        overflowWrap: 'anywhere',
      }}
    >
      {caption}
    </span>
  ) : null;

  if (kind === 'pdf' || kind === 'word' || kind === 'excel' || kind === 'csv') {
    // Non-image kinds render as a compact chip with filename + optional
    // counter (PDF pages / Excel sheets). Click opens the bytes in a new tab
    // once fetched. PDF rendering of pages requires pdfjs-dist (deferred);
    // Word/Excel/CSV chips never get a visual thumbnail.
    const meta = CHIP_META[kind];
    const onClick = () => {
      if (loadState === 'loaded' && blobUrl) {
        window.open(blobUrl, '_blank', 'noopener,noreferrer');
      }
    };
    // Per-kind metadata suffix. PDF → "· Np", Excel → "· Ns", Word/CSV → none.
    let suffix: string | null = null;
    if (kind === 'pdf' && typeof pageCount === 'number' && pageCount > 0) {
      suffix = `· ${pageCount}p`;
    } else if (kind === 'excel' && typeof sheetCount === 'number' && sheetCount > 0) {
      suffix = `· ${sheetCount}s`;
    }
    // W2 a11y — chip is the primary interaction (click → open blob in new tab).
    // `<Tag>` renders as `<span>` and is not keyboard-focusable by default;
    // promote to role=button + tabIndex so keyboard-only users can Tab to it
    // and activate via Enter/Space. tabIndex=-1 while loading keeps it out of
    // the tab order until the blob URL is available (matching the cursor=wait
    // affordance).
    const onKeyDown = (e: React.KeyboardEvent<HTMLSpanElement>) => {
      if ((e.key === 'Enter' || e.key === ' ') && loadState === 'loaded' && blobUrl) {
        e.preventDefault();
        onClick();
      }
    };
    const tooltip = loadState === 'loaded'
      ? 'Click to open in a new tab'
      : loadState === 'error' ? 'Attachment could not be loaded' : 'Loading attachment';
    return (
      <div style={{ display: 'inline-flex', flexDirection: 'column', alignItems: 'flex-start', gap: 4 }}>
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          <Tooltip title={tooltip}>
            <Tag
              color={loadState === 'error' ? 'error' : 'default'}
              onClick={onClick}
              onKeyDown={onKeyDown}
              tabIndex={loadState === 'loaded' ? 0 : -1}
              role="button"
              aria-disabled={loadState !== 'loaded'}
              aria-label={`Open ${filename} in a new tab`}
              style={{
                cursor: loadState === 'loaded' ? 'pointer' : loadState === 'loading' ? 'wait' : 'default',
                padding: '4px 10px',
                fontSize: 12,
                display: 'inline-flex',
                alignItems: 'center',
                gap: 6,
                borderRadius: 8,
                margin: 0,
              }}
              data-testid={meta.testId}
            >
              {meta.icon}
              <span style={{ fontWeight: 500 }}>{filename}</span>
              {suffix && (
                <span style={{ color: 'var(--fg-4, #8a8a93)', fontSize: 11 }}>
                  {suffix}
                </span>
              )}
              {loadState === 'loading' && <span role="status"><LoadingOutlined /> Loading</span>}
              {loadState === 'error' && <span role="alert">Load failed</span>}
            </Tag>
          </Tooltip>
          {loadState === 'error' && (
            <button
              type="button"
              onClick={retry}
              aria-label={`Retry loading ${filename}`}
              title={`Retry loading ${filename}`}
              style={{
                border: '1px solid var(--border-1, #444)',
                background: 'var(--bg-2, transparent)',
                color: 'var(--fg-2, inherit)',
                borderRadius: 4,
                padding: '4px 8px',
                cursor: 'pointer',
              }}
            >
              <ReloadOutlined /> Retry
            </button>
          )}
        </div>
        {captionNode}
      </div>
    );
  }

  // image kind — render inline blob URL with AntD Image preview behavior.
  if (loadState === 'error') {
    return (
      <div style={{ display: 'inline-flex', flexDirection: 'column', alignItems: 'flex-start', gap: 4 }}>
        <Tag color="error" role="alert" style={{ padding: '4px 10px', borderRadius: 8, margin: 0 }}>
          {filename} · Load failed
        </Tag>
        <button
          type="button"
          onClick={retry}
          aria-label={`Retry loading ${filename}`}
          title={`Retry loading ${filename}`}
          style={{
            border: '1px solid var(--border-1, #444)',
            background: 'var(--bg-2, transparent)',
            color: 'var(--fg-2, inherit)',
            borderRadius: 4,
            padding: '4px 8px',
            cursor: 'pointer',
          }}
        >
          <ReloadOutlined /> Retry
        </button>
        {captionNode}
      </div>
    );
  }
  if (loadState === 'loading' || !blobUrl) {
    return (
      <div style={{ display: 'inline-flex', flexDirection: 'column', alignItems: 'flex-start', gap: 4 }}>
        <div
          data-testid="attachment-image-skeleton"
          role="status"
          aria-label={`Loading ${filename}`}
          style={{
            width: 200,
            height: 150,
            borderRadius: 8,
            background: 'var(--bg-3, rgba(255,255,255,0.04))',
            animation: 'pulse 1.4s ease-in-out infinite',
            display: 'grid',
            placeItems: 'center',
            color: 'var(--fg-3, #8a8a93)',
            fontSize: 12,
          }}
        >
          <LoadingOutlined />
        </div>
        {captionNode}
      </div>
    );
  }
  return (
    <div style={{ display: 'inline-flex', flexDirection: 'column', alignItems: 'flex-start', gap: 4 }}>
      <AntdImage
        src={blobUrl}
        alt={filename}
        onError={() => setLoadState('error')}
        // Inline preview — click opens AntD's full-screen lightbox. Bounded so
        // a 4K screenshot doesn't take over the chat bubble.
        style={{
          width: 200,
          height: 150,
          borderRadius: 8,
          objectFit: 'cover',
          display: 'block',
        }}
        preview={{ cover: <span style={{ fontSize: 12 }}>Preview</span> }}
        data-testid="attachment-image-thumb"
      />
      {captionNode}
    </div>
  );
};

export default React.memo(AttachmentThumbnail);
