import { useQuery } from '@tanstack/react-query';
import { getLlmModels } from '../api';
import { FALLBACK_MODEL_OPTIONS, type ModelOption, type ProtocolFamily } from '../constants/models';

/**
 * Shape that may come back from `/api/llm/models` during rollout. The backend is
 * being extended to include `supportsThinking` / `supportsReasoningEffort` /
 * `protocolFamily`; until that ships, requests may return only the legacy
 * 5 fields. We normalize missing capability flags to `false` so the UI never
 * assumes a model supports thinking it doesn't actually support.
 */
type RawModelOption = Omit<ModelOption, 'supportsThinking' | 'supportsReasoningEffort' | 'protocolFamily'> & {
  supportsThinking?: boolean;
  supportsReasoningEffort?: boolean;
  protocolFamily?: ProtocolFamily;
};

function normalize(raw: RawModelOption): ModelOption {
  return {
    id: raw.id,
    label: raw.label,
    provider: raw.provider,
    model: raw.model,
    isDefault: raw.isDefault,
    supportsThinking: raw.supportsThinking ?? false,
    supportsReasoningEffort: raw.supportsReasoningEffort ?? false,
    protocolFamily: raw.protocolFamily,
  };
}

export function useLlmModels(): { options: ModelOption[]; isLoading: boolean } {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['llm-models'],
    queryFn: () => getLlmModels().then((r) => r.data),
    staleTime: 5 * 60 * 1000, // 5 min
    retry: 1,
  });
  const options =
    isError || !data || data.length === 0
      ? FALLBACK_MODEL_OPTIONS
      : (data as RawModelOption[]).map(normalize);
  return { options, isLoading };
}
