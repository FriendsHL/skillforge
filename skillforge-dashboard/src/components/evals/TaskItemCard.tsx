import { useState } from 'react';
import { Link } from 'react-router-dom';
import type { EvalTaskItem } from '../../api';
import type { EvalMetric } from './evalUtils';
import { getMetricValue, formatMetricValue, scoreColor, TRACE_ICON, ANALYZE_ICON, ANNOTATE_ICON } from './evalUtils';

interface TaskItemCardProps {
  item: EvalTaskItem;
  metric: EvalMetric;
  onAnalyze: () => void;
  onAnnotate: () => void;
}

export default function TaskItemCard({ item, metric, onAnalyze, onAnnotate }: TaskItemCardProps) {
  const [showRationale, setShowRationale] = useState(false);
  const [showOutput, setShowOutput] = useState(false);
  const score = getMetricValue(item, metric) ?? item.compositeScore ?? 0;
  const score01 = score / 100;
  const tier = score >= 80 ? 'pass' : score >= 60 ? 'warn' : item.status === 'PASS' ? 'pass' : 'fail';

  const attribution = item.attribution ?? 'NONE';
  const isFailedAttr = attribution !== 'NONE';

  return (
    <div className={`scn-result-card s-${tier}`}>
      {/* Header: name + status + score */}
      <div className="scn-result-h">
        <div className="scn-result-h-l">
          <span className="scn-result-name">{item.scenarioId}</span>
          <span className={`sess-status s-${item.status === 'PASS' ? 'idle' : item.status === 'TIMEOUT' ? 'waiting' : 'error'}`}>
            {item.status}
          </span>
          {isFailedAttr && (
            <span className="scn-result-attr attr-fail">
              {attribution.toLowerCase().replace(/_/g, ' ')}
            </span>
          )}
        </div>
        <div className="scn-result-score" style={{ color: scoreColor(score01) }}>
          {formatMetricValue(score)}<em>{metric === 'composite' ? '' : ` · ${metric}`}</em>
        </div>
      </div>

      {/* Metric chips row */}
      <div className="scn-result-chips">
        {item.scenarioSource && <span className="kv-chip-sf">{item.scenarioSource}</span>}
        <span className={`kv-chip-sf ${metric === 'composite' ? 'on' : ''}`}>composite · {formatMetricValue(item.compositeScore)}</span>
        <span className={`kv-chip-sf ${metric === 'quality' ? 'on' : ''}`}>quality · {formatMetricValue(item.qualityScore)}</span>
        <span className={`kv-chip-sf ${metric === 'efficiency' ? 'on' : ''}`}>efficiency · {formatMetricValue(item.efficiencyScore)}</span>
        <span className={`kv-chip-sf ${metric === 'latency' ? 'on' : ''}`}>latency · {formatMetricValue(item.latencyScore)}</span>
        <span className={`kv-chip-sf ${metric === 'cost' ? 'on' : ''}`}>cost · {formatMetricValue(item.costScore)}</span>
        {item.costUsd != null && <span className="kv-chip-sf">cost · ${item.costUsd.toFixed(4)}</span>}
        {item.loopCount != null && <span className="kv-chip-sf">loops · {item.loopCount}</span>}
        {item.toolCallCount != null && <span className="kv-chip-sf">tools · {item.toolCallCount}</span>}
        {item.latencyMs != null && <span className="kv-chip-sf">latency · {item.latencyMs}ms</span>}
      </div>

      {/* Collapsible sections */}
      {item.judgeRationale && (
        <>
          <button className="scn-result-disclosure" onClick={() => setShowRationale(v => !v)}>
            {showRationale ? '▾' : '▸'} judge rationale
          </button>
          {showRationale && <div className="scn-result-section">{item.judgeRationale}</div>}
        </>
      )}
      {item.agentFinalOutput && (
        <>
          <button className="scn-result-disclosure" onClick={() => setShowOutput(v => !v)}>
            {showOutput ? '▾' : '▸'} agent final output
          </button>
          {showOutput && <div className="scn-result-section mono">{item.agentFinalOutput}</div>}
        </>
      )}

      {/* Actions: trace jump is now primary, analyze/annotate are icon buttons */}
      <div className="scn-result-actions">
        {item.rootTraceId && (
          <Link className="sf-mini-btn trace-btn" to={`/traces?traceId=${encodeURIComponent(item.rootTraceId)}`} title="View trace">
            {TRACE_ICON} Trace
          </Link>
        )}
        <button className="sf-mini-btn icon-btn" onClick={onAnalyze} title="Analyze this case">
          {ANALYZE_ICON}
        </button>
        <button className="sf-mini-btn icon-btn" onClick={onAnnotate} title="Annotate / correct score">
          {ANNOTATE_ICON}
        </button>
      </div>
    </div>
  );
}
