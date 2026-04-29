import React, { useState } from 'react';
import axios from 'axios';
import type { BlobPart, BlobStatus } from '../../../types/observability';
import PayloadJsonView from './PayloadJsonView';
import PayloadSourceBadge from './PayloadSourceBadge';

interface PayloadViewerProps {
  /** ≤ 32KB summary text rendered immediately (without hitting blob endpoint). */
  summary: string | null;
  /** Title shown on the section, e.g. "Request" / "Response" / "Raw SSE". */
  title: string;
  /** Backend blob_status; controls disabled / tooltip / alert behavior. */
  blobStatus: BlobStatus | null | undefined;
  /** Whether the underlying blob exists for the requested part. */
  hasBlob: boolean;
  /** Which blob part this viewer renders. */
  part: BlobPart;
  /** Loader for the full blob (called once on demand; result cached in state). */
  loadFullBlob: (part: BlobPart) => Promise<string>;
  /** Optional ARIA test id for stable querying in tests. */
  testId?: string;
}

interface BlobState {
  loading: boolean;
  text: string | null;
  error: string | null;
}

/**
 * Default-summary + on-demand-full payload viewer.
 *
 * Supports four blob_status modes (plan §8.4):
 *   - ok          → button enabled, regular flow
 *   - legacy      → button disabled, "历史 session 无 raw payload"
 *   - write_failed→ button disabled, "blob 写入失败（live span 数据已落 DB 摘要…）"
 *   - truncated   → button enabled; after load, show Alert "payload 超过 50MB 已截断"
 */
const PayloadViewer: React.FC<PayloadViewerProps> = ({
  summary,
  title,
  blobStatus,
  hasBlob,
  part,
  loadFullBlob,
  testId,
}) => {
  const [blob, setBlob] = useState<BlobState>({ loading: false, text: null, error: null });

  const isLegacy = blobStatus === 'legacy';
  const isWriteFailed = blobStatus === 'write_failed';
  const isTruncated = blobStatus === 'truncated';
  const buttonDisabled =
    isLegacy || isWriteFailed || !hasBlob || blob.loading || blob.text != null;

  const buttonTooltip = isLegacy
    ? '历史 session 无 raw payload'
    : isWriteFailed
      ? 'blob 写入失败（live span 数据已落 DB 摘要，可联系管理员排查）'
      : !hasBlob
        ? 'blob 不存在'
        : '加载完整 payload';

  const handleLoad = async () => {
    if (buttonDisabled) return;
    setBlob({ loading: true, text: null, error: null });
    try {
      const text = await loadFullBlob(part);
      setBlob({ loading: false, text, error: null });
    } catch (err: unknown) {
      // FE-W1 fix: surface a friendly message when backend Semaphore (plan §6.2 R3-WN3)
      // rejects the request with 429; everything else falls back to a generic message
      // to avoid leaking server-side error details.
      let message = 'load failed';
      if (axios.isAxiosError(err) && err.response?.status === 429) {
        message = '服务繁忙，请稍后重试';
      } else if (err instanceof Error) {
        message = err.message;
      }
      setBlob({ loading: false, text: null, error: message });
    }
  };

  return (
    <section className="obs-payload-viewer" data-testid={testId}>
      <header className="obs-payload-head">
        <h4 className="obs-payload-title">{title}</h4>
        <PayloadSourceBadge blobStatus={blobStatus} />
        <button
          type="button"
          className="obs-payload-load-btn"
          onClick={handleLoad}
          disabled={buttonDisabled}
          title={buttonTooltip}
          aria-label={`Load full ${title}`}
        >
          {blob.text != null
            ? '已加载'
            : blob.loading
              ? '加载中…'
              : `加载完整 ${title}`}
        </button>
      </header>

      {isTruncated && (
        <div className="obs-payload-alert obs-payload-alert--warn" role="alert">
          payload 超过 50MB 已截断，可下载查看完整内容
        </div>
      )}

      <div className="obs-payload-summary">
        {summary && summary.length > 0 ? (
          <pre className="obs-payload-pre obs-payload-pre--summary">{summary}</pre>
        ) : (
          <div className="obs-payload-empty">无摘要</div>
        )}
      </div>

      {blob.error && (
        <div className="obs-payload-alert obs-payload-alert--error" role="alert">
          加载失败: {blob.error}
        </div>
      )}

      {blob.text != null && (
        <div className="obs-payload-full">
          <PayloadJsonView text={blob.text} />
        </div>
      )}
    </section>
  );
};

export default PayloadViewer;
