import React, { useCallback } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { LlmSpanSummary, LlmSpanDetail, BlobPart } from '../../../types/observability';
import { getLlmSpanDetail, getLlmSpanBlob } from '../../../api';
import { useAuth } from '../../../contexts/AuthContext';
import PayloadViewer from './PayloadViewer';

interface LlmSpanDetailViewProps {
  span: LlmSpanSummary;
}

const LlmSpanDetailView: React.FC<LlmSpanDetailViewProps> = ({ span }) => {
  const { userId } = useAuth();
  const { data, isLoading, isError, refetch } = useQuery<LlmSpanDetail>({
    queryKey: ['obs-llm-span-detail', span.spanId, userId],
    queryFn: async () => {
      const res = await getLlmSpanDetail(span.spanId, userId);
      return res.data;
    },
    staleTime: 60_000,
  });

  // Stable loader — closes over spanId only (not over `data`), so it doesn't
  // change between calls. PayloadViewer keeps its own loaded-state.
  const loadFullBlob = useCallback(
    async (part: BlobPart): Promise<string> => {
      const res = await getLlmSpanBlob(span.spanId, part, userId);
      // axios `responseType: 'text'` + transformResponse → res.data is string.
      return typeof res.data === 'string' ? res.data : String(res.data ?? '');
    },
    [span.spanId, userId],
  );

  if (isLoading) {
    return <div className="obs-empty-state">Loading LLM span detail…</div>;
  }
  if (isError || !data) {
    return (
      <div className="obs-empty-state">
        Failed to load LLM span detail.{' '}
        <button type="button" className="btn-ghost-sf" onClick={() => refetch()}>
          Retry
        </button>
      </div>
    );
  }

  // Token totals come from the summary DTO; backend detail DTO does not repeat
  // them (it carries `cacheReadTokens` + the raw `usage` blob instead).
  const totalIn = span.inputTokens;
  const totalOut = span.outputTokens;

  return (
    <div className="obs-llm-span-detail">
      <header className="obs-span-meta">
        <div className="obs-span-meta-row">
          <span className="obs-span-meta-k">provider</span>
          <span className="obs-span-meta-v mono-sm">{data.provider ?? '—'}</span>
        </div>
        <div className="obs-span-meta-row">
          <span className="obs-span-meta-k">model</span>
          <span className="obs-span-meta-v mono-sm">{data.model ?? '—'}</span>
        </div>
        <div className="obs-span-meta-row">
          <span className="obs-span-meta-k">tokens</span>
          <span className="obs-span-meta-v mono-sm">
            in {totalIn.toLocaleString()} / out {totalOut.toLocaleString()}
            {data.cacheReadTokens != null ? ` / cache ${data.cacheReadTokens.toLocaleString()}` : ''}
          </span>
        </div>
        <div className="obs-span-meta-row">
          <span className="obs-span-meta-k">latency</span>
          <span className="obs-span-meta-v mono-sm">{data.latencyMs} ms</span>
        </div>
        {data.finishReason && (
          <div className="obs-span-meta-row">
            <span className="obs-span-meta-k">finishReason</span>
            <span className="obs-span-meta-v mono-sm">{data.finishReason}</span>
          </div>
        )}
        {data.error && (
          <div className="obs-span-meta-row obs-span-meta-row--error">
            <span className="obs-span-meta-k">error</span>
            <span className="obs-span-meta-v mono-sm">
              {data.errorType ? `[${data.errorType}] ` : ''}
              {data.error}
            </span>
          </div>
        )}
      </header>

      <PayloadViewer
        title="原始请求 (Request)"
        summary={data.inputSummary}
        blobStatus={data.blobStatus}
        hasBlob={data.blobs.hasRawRequest}
        part="request"
        loadFullBlob={loadFullBlob}
        testId="llm-payload-request"
      />
      <PayloadViewer
        title="原始响应 (Response)"
        summary={data.outputSummary}
        blobStatus={data.blobStatus}
        hasBlob={data.blobs.hasRawResponse}
        part="response"
        loadFullBlob={loadFullBlob}
        testId="llm-payload-response"
      />
      {data.stream && data.blobs.hasRawSse && (
        <PayloadViewer
          title="Raw SSE"
          summary={null}
          blobStatus={data.blobStatus}
          hasBlob={data.blobs.hasRawSse}
          part="sse"
          loadFullBlob={loadFullBlob}
          testId="llm-payload-sse"
        />
      )}

      {data.reasoningContent && data.reasoningContent.length > 0 && (
        <section className="obs-payload-viewer">
          <header className="obs-payload-head">
            <h4 className="obs-payload-title">Reasoning</h4>
          </header>
          <pre className="obs-payload-pre">{data.reasoningContent}</pre>
        </section>
      )}
    </div>
  );
};

export default LlmSpanDetailView;
