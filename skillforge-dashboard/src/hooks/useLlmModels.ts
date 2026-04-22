import { useQuery } from '@tanstack/react-query';
import { getLlmModels } from '../api';
import { FALLBACK_MODEL_OPTIONS, type ModelOption } from '../constants/models';

export function useLlmModels(): { options: ModelOption[]; isLoading: boolean } {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['llm-models'],
    queryFn: () => getLlmModels().then((r) => r.data),
    staleTime: 5 * 60 * 1000, // 5 min
    retry: 1,
  });
  const options = isError || !data || data.length === 0 ? FALLBACK_MODEL_OPTIONS : data;
  return { options, isLoading };
}
