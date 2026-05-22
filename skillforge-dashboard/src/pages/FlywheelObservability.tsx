import React from 'react';
import FlywheelFlowchart from '../components/flywheel/FlywheelFlowchart';
import FlywheelChainRuns from '../components/flywheel/FlywheelChainRuns';

/**
 * FLYWHEEL-FLOWCHART + FLYWHEEL-CHAIN-VISIBILITY — page wrapper for the
 * Insights > Flywheel tab.
 *
 * Layout:
 *   1. <FlywheelChainRuns /> — top section, surfaces recent annotator →
 *      dispatcher chain runs from on-demand "Run Opt Loop" invocations
 *      (gap B). Polls every 5s so running chains visibly progress.
 *   2. <FlywheelFlowchart /> — the existing DAG view (aggregate or per-run).
 *
 * Chain Runs panel sits above the flowchart because operators triggering
 * the on-demand loop care first about "did my run produce results"; the
 * DAG below is the broader observability surface.
 */
const FlywheelObservability: React.FC = () => {
  return (
    <div className="fw-observability-page">
      <FlywheelChainRuns />
      <FlywheelFlowchart />
    </div>
  );
};

export default FlywheelObservability;
