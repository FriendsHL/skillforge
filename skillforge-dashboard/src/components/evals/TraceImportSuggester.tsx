import React, { useState } from 'react';
import { Modal, Button, Tag, Spin, Empty, Tooltip } from 'antd';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { message } from 'antd';
import { suggestTracesForDataset, batchImportTracesToDataset, type TraceImportCandidate, type TraceSuggestionFilter } from '../../api';

interface TraceImportSuggesterProps {
  open: boolean;
  onClose: () => void;
}

export default function TraceImportSuggester({ open, onClose }: TraceImportSuggesterProps) {
  const queryClient = useQueryClient();
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [filter, setFilter] = useState<TraceSuggestionFilter>({ minTokens: 500, limit: 50 });

  // Fetch suggestions from backend agent
  const { data: suggestions = [], isLoading } = useQuery({
    queryKey: ['trace-suggestions', filter],
    queryFn: () => suggestTracesForDataset(filter).then(res => res.data ?? []),
    enabled: open,
  });

  // Batch import mutation
  const importMutation = useMutation({
    mutationFn: (ids: string[]) => batchImportTracesToDataset({ rootTraceIds: ids }),
    onSuccess: (res) => {
      message.success(`Successfully imported ${res.data.count} scenarios!`);
      queryClient.invalidateQueries({ queryKey: ['eval-dataset-scenarios'] });
      onClose();
    },
    onError: () => message.error('Import failed'),
  });

  const toggleSelect = (id: string) => {
    const next = new Set(selectedIds);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setSelectedIds(next);
  };

  const selectAll = () => {
    if (selectedIds.size === suggestions.length) setSelectedIds(new Set());
    else setSelectedIds(new Set(suggestions.map(s => s.rootTraceId)));
  };

  return (
    <Modal
      title="✨ Smart Dataset Extractor"
      open={open}
      onCancel={onClose}
      width={900}
      footer={[
        <Button key="close" onClick={onClose}>Cancel</Button>,
        <Button 
          key="import" 
          type="primary" 
          loading={importMutation.isPending}
          disabled={selectedIds.size === 0}
          onClick={() => importMutation.mutate(Array.from(selectedIds))}
        >
          Import {selectedIds.size} Sessions
        </Button>
      ]}
    >
      <div style={{ marginBottom: 16, display: 'flex', gap: 12, alignItems: 'center', background: 'var(--bg-base)', padding: 12, borderRadius: 8 }}>
        <span style={{ fontSize: 13, fontWeight: 500 }}>Strategy:</span>
        <Button size="small" type={!filter.hasToolCalls && !filter.minTokens ? 'primary' : 'default'} onClick={() => setFilter({ limit: 50 })}>
          📋 All Recent
        </Button>
        <Button size="small" type={filter.minTokens === 500 ? 'primary' : 'default'} onClick={() => setFilter({ minTokens: 500, limit: 50 })}>
          💬 Multi-turn (&gt;500 tok)
        </Button>
        <Button size="small" type={filter.hasToolCalls ? 'primary' : 'default'} onClick={() => setFilter({ hasToolCalls: true, limit: 50 })}>
          🔧 With Tool Calls
        </Button>
      </div>

      {isLoading ? (
        <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
      ) : (
        <div style={{ maxHeight: 450, overflowY: 'auto', border: '1px solid var(--border-1)', borderRadius: 8 }}>
          <div style={{ padding: 10, borderBottom: '1px solid var(--border-1)', background: 'var(--bg-hover)', display: 'flex', justifyContent: 'space-between' }}>
            <Button size="small" onClick={selectAll}>
              {selectedIds.size === suggestions.length ? 'Deselect All' : 'Select All'}
            </Button>
            <span style={{ fontSize: 12, color: 'var(--fg-3)' }}>
              Found {suggestions.length} candidates
            </span>
          </div>
          
          {suggestions.length === 0 ? (
            <Empty description="No traces match the current strategy." style={{ marginTop: 40 }} />
          ) : (
            suggestions.map((trace: TraceImportCandidate) => (
              <div 
                key={trace.rootTraceId} 
                onClick={() => toggleSelect(trace.rootTraceId)}
                style={{ 
                  padding: 14, 
                  borderBottom: '1px solid var(--border-1)', 
                  cursor: 'pointer',
                  background: selectedIds.has(trace.rootTraceId) ? 'rgba(99, 102, 241, 0.08)' : 'transparent',
                  transition: 'background 0.2s'
                }}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
                  <div style={{ fontWeight: 600, fontSize: 14, color: 'var(--fg-1)' }}>
                    {trace.agentName || 'Agent Loop'}
                    {trace.reasonCodes && trace.reasonCodes.length > 0 ? (
                      <Tooltip title={trace.reasonCodes.join(', ')}>
                        <Tag color="blue" style={{ marginLeft: 8 }}>Recommended</Tag>
                      </Tooltip>
                    ) : null}
                  </div>
                  <Tag color={trace.status === 'ok' ? 'success' : trace.status === 'error' ? 'error' : 'default'}>{trace.status}</Tag>
                </div>
                <div style={{ fontSize: 12, color: 'var(--fg-3)', display: 'flex', gap: 16 }}>
                  <span>ID: {trace.rootTraceId.slice(0, 12)}...</span>
                  <span>Tokens: {trace.tokenCount.toLocaleString()}</span>
                  <span>LLM: {trace.llmCallCount}</span>
                  <span>Tools: {trace.toolCallCount}</span>
                </div>
                {trace.preview && (
                  <div style={{ marginTop: 6, fontSize: 11, color: 'var(--fg-4)', fontStyle: 'italic' }}>
                    "{trace.preview.slice(0, 100)}..."
                  </div>
                )}
              </div>
            ))
          )}
        </div>
      )}
    </Modal>
  );
}
