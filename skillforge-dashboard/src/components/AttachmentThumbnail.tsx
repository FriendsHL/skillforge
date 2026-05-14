import React, { useEffect, useState } from 'react';
import { Image as AntdImage, Tag, Tooltip } from 'antd';
import {
  FilePdfOutlined,
  FileWordOutlined,
  FileExcelOutlined,
  FileTextOutlined,
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
}

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
}) => {
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    let createdUrl: string | null = null;
    setFailed(false);
    setBlobUrl(null);
    getChatAttachmentBlob(attachmentId, userId, sessionId)
      .then((res) => {
        if (cancelled) return;
        // axios with responseType:'blob' returns the Blob in res.data
        const url = URL.createObjectURL(res.data as unknown as Blob);
        createdUrl = url;
        setBlobUrl(url);
      })
      .catch(() => {
        if (cancelled) return;
        setFailed(true);
      });
    return () => {
      cancelled = true;
      if (createdUrl) {
        URL.revokeObjectURL(createdUrl);
      }
    };
  }, [attachmentId, userId, sessionId]);

  if (kind === 'pdf' || kind === 'word' || kind === 'excel' || kind === 'csv') {
    // Non-image kinds render as a compact chip with filename + optional
    // counter (PDF pages / Excel sheets). Click opens the bytes in a new tab
    // once fetched. PDF rendering of pages requires pdfjs-dist (deferred);
    // Word/Excel/CSV chips never get a visual thumbnail.
    const meta = CHIP_META[kind];
    const onClick = () => {
      if (blobUrl) {
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
      if ((e.key === 'Enter' || e.key === ' ') && blobUrl) {
        e.preventDefault();
        onClick();
      }
    };
    return (
      <Tooltip title={blobUrl ? 'Click to open in a new tab' : 'Loading…'}>
        <Tag
          color="default"
          onClick={onClick}
          onKeyDown={onKeyDown}
          tabIndex={blobUrl ? 0 : -1}
          role="button"
          aria-label={`Open ${filename} in a new tab`}
          style={{
            cursor: blobUrl ? 'pointer' : 'wait',
            padding: '4px 10px',
            fontSize: 12,
            display: 'inline-flex',
            alignItems: 'center',
            gap: 6,
            borderRadius: 8,
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
        </Tag>
      </Tooltip>
    );
  }

  // image kind — render inline blob URL with AntD Image preview behavior.
  if (failed) {
    return (
      <Tag color="error" style={{ padding: '4px 10px', borderRadius: 8 }}>
        🖼️ {filename} (load failed)
      </Tag>
    );
  }
  if (!blobUrl) {
    return (
      <div
        data-testid="attachment-image-skeleton"
        style={{
          width: 96,
          height: 96,
          borderRadius: 8,
          background: 'var(--bg-3, rgba(255,255,255,0.04))',
          animation: 'pulse 1.4s ease-in-out infinite',
        }}
      />
    );
  }
  return (
    <AntdImage
      src={blobUrl}
      alt={filename}
      // Inline preview — click opens AntD's full-screen lightbox. Bounded so
      // a 4K screenshot doesn't take over the chat bubble.
      style={{
        maxWidth: 200,
        maxHeight: 200,
        borderRadius: 8,
        objectFit: 'cover',
        display: 'block',
      }}
      preview={{ mask: <span style={{ fontSize: 12 }}>Preview</span> }}
      data-testid="attachment-image-thumb"
    />
  );
};

export default React.memo(AttachmentThumbnail);
