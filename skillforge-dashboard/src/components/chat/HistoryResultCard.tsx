import React, { useMemo } from 'react';
import {
  decodeHistoryToolResult,
  type HistoryReadResponse,
  type HistorySearchResponse,
} from '../../history/historyResultDecoder';

export interface HistoryResultCardProps {
  output?: string;
}

const HistoryResultCard: React.FC<HistoryResultCardProps> = ({ output = '' }) => {
  const decoded = useMemo(() => decodeHistoryToolResult(output), [output]);
  if (!decoded.ok) {
    return (
      <div className="history-result-card history-result-invalid" role="status">
        <strong>History result unavailable</strong>
        <span>The result failed safe display validation.</span>
      </div>
    );
  }

  const value = decoded.value;
  if ('error' in value) {
    return (
      <div className="history-result-card history-result-error" role="status">
        <strong>{value.error.code}</strong>
        <span>{value.error.message}</span>
      </div>
    );
  }
  if ('locators' in value) {
    const search = value as HistorySearchResponse;
    return (
      <div className="history-result-card" data-testid="history-search-result">
        <div className="history-result-heading">
          History search · {search.locators.length} result{search.locators.length === 1 ? '' : 's'}
        </div>
        {search.locators.map((locator) => (
          <div className="history-result-item" key={locator.ref}>
            <div className="history-result-meta">
              <span>{locator.evidenceClass}</span>
              <span>seq {locator.logicalSeq}</span>
              <code>{locator.ref}</code>
            </div>
            <pre>{locator.preview}</pre>
          </div>
        ))}
      </div>
    );
  }

  const read = value as HistoryReadResponse;
  return (
    <div className="history-result-card" data-testid="history-read-result">
      <div className="history-result-heading">
        History evidence · {read.events.length} event{read.events.length === 1 ? '' : 's'}
      </div>
      {read.events.map((event, index) => (
        <div className="history-result-item" key={`${event.ref}:${event.codePointOffset}:${index}`}>
          <div className="history-result-meta">
            <span>{event.evidenceClass}</span>
            <span>seq {event.logicalSeq}</span>
            <code>{event.ref}</code>
          </div>
          <pre>{event.content}</pre>
        </div>
      ))}
    </div>
  );
};

export default HistoryResultCard;
