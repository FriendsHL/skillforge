import React, { useMemo, useState } from 'react';
import JsonViewer from '../../JsonViewer';

interface PayloadJsonViewProps {
  /** Raw payload text (typically JSON or SSE plaintext). */
  text: string;
}

const PRETTY_THRESHOLD_BYTES = 200_000; // > 200KB: pretty render flat root only
const PLAIN_THRESHOLD_BYTES = 2_000_000; // > 2MB: render as <pre>, offer download

/**
 * Render a payload string with progressive degradation per plan §8.5:
 * - try parse as JSON; on failure render plaintext <pre>
 * - if size > 2MB: skip JSON parsing, dump as plain <pre> + download button
 * - if 200KB < size <= 2MB: parse but only render top-level (caller can drill in)
 *
 * Reuses the existing project-local JsonViewer; introduces no new deps.
 */
const PayloadJsonView: React.FC<PayloadJsonViewProps> = ({ text }) => {
  const [forceRaw, setForceRaw] = useState(false);
  const sizeBytes = useMemo(() => {
    // Approximate UTF-8 byte size without a heavy encoder.
    if (typeof TextEncoder !== 'undefined') {
      try {
        return new TextEncoder().encode(text).length;
      } catch {
        return text.length;
      }
    }
    return text.length;
  }, [text]);

  const isHuge = sizeBytes > PLAIN_THRESHOLD_BYTES;
  const isLarge = !isHuge && sizeBytes > PRETTY_THRESHOLD_BYTES;

  const parsed = useMemo<{ kind: 'json' | 'plain'; data: unknown }>(() => {
    if (isHuge || forceRaw) return { kind: 'plain', data: text };
    try {
      return { kind: 'json', data: JSON.parse(text) };
    } catch {
      return { kind: 'plain', data: text };
    }
  }, [text, isHuge, forceRaw]);

  const handleDownload = () => {
    const blob = new Blob([text], { type: 'application/octet-stream' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `payload-${Date.now()}.txt`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  return (
    <div className="obs-payload-json">
      <div className="obs-payload-json-meta">
        <span className="mono-sm">{(sizeBytes / 1024).toFixed(1)} KB</span>
        {isLarge && !isHuge && !forceRaw && (
          <button type="button" className="mini-btn" onClick={() => setForceRaw(true)}>
            Show as text
          </button>
        )}
        <button type="button" className="mini-btn" onClick={handleDownload}>
          Download
        </button>
      </div>
      {parsed.kind === 'json' ? (
        <JsonViewer data={parsed.data} />
      ) : (
        <pre className="obs-payload-pre">{typeof parsed.data === 'string' ? parsed.data : ''}</pre>
      )}
    </div>
  );
};

export default PayloadJsonView;
