import { useCallback, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  getBehaviorRules,
  type BehaviorRule,
  type BehaviorRuleConfig,
  type CustomRuleSeverity,
} from '../api';
import {
  CATEGORY_META,
  SEVERITY_ORDER,
  DEFAULT_TEMPLATE,
  type RuleTemplateId,
} from '../constants/behaviorRules';

export interface GroupedCategory {
  key: string;
  label: string;
  iconName: string;
  rules: Array<BehaviorRule & { enabled: boolean }>;
  enabledCount: number;
}

interface UseBehaviorRulesReturn {
  /** All builtin rules from API */
  builtinRules: BehaviorRule[];
  /** Current config state */
  config: BehaviorRuleConfig;
  /** Current template ID */
  templateId: RuleTemplateId;
  /** Rules grouped by category with enabled status */
  groupedRules: GroupedCategory[];
  /** Apply a preset template */
  applyTemplate: (id: Exclude<RuleTemplateId, 'custom'>) => void;
  /** Toggle a single rule on/off */
  toggleRule: (ruleId: string, enabled: boolean) => void;
  /** Add a custom rule */
  addCustomRule: (text: string, severity?: CustomRuleSeverity) => void;
  /** Remove a custom rule by index */
  removeCustomRule: (index: number) => void;
  /** Update a custom rule at index */
  updateCustomRule: (index: number, text: string, severity?: CustomRuleSeverity) => void;
  /** Set config from external source (e.g., when editing an agent) */
  setConfig: (config: BehaviorRuleConfig) => void;
  /** Whether the builtin rules are still loading */
  isLoading: boolean;
}

const EMPTY_CONFIG: BehaviorRuleConfig = { builtinRuleIds: [], customRules: [] };

export function useBehaviorRules(
  initialConfig: BehaviorRuleConfig | null,
  executionMode: string,
): UseBehaviorRulesReturn {
  const { data: rulesResponse, isLoading } = useQuery({
    queryKey: ['behavior-rules', 'builtin'],
    queryFn: () => getBehaviorRules().then((r) => r.data),
    staleTime: 86_400_000, // 1 day
  });

  const builtinRules = rulesResponse?.rules ?? [];

  const [config, setConfigState] = useState<BehaviorRuleConfig>(
    initialConfig ?? EMPTY_CONFIG,
  );
  const [templateId, setTemplateId] = useState<RuleTemplateId>(() => {
    if (initialConfig && initialConfig.builtinRuleIds.length > 0) return 'custom';
    return DEFAULT_TEMPLATE[executionMode] ?? 'cautious';
  });

  // Derive preset rule IDs from builtinRules
  const presetMap = useMemo(() => {
    const map: Record<string, string[]> = {};
    for (const preset of ['autonomous', 'cautious', 'full'] as const) {
      map[preset] = builtinRules
        .filter((r) => !r.deprecated && r.presets.includes(preset))
        .map((r) => r.id);
    }
    return map;
  }, [builtinRules]);

  // Auto-apply default template when rules load and no initial config
  const configInitialized = useMemo(() => {
    if (initialConfig) return true;
    if (builtinRules.length === 0) return false;
    return config.builtinRuleIds.length > 0;
  }, [initialConfig, builtinRules.length, config.builtinRuleIds.length]);

  // Apply default preset on first load if no initial config
  if (!configInitialized && builtinRules.length > 0 && !initialConfig) {
    const defaultPreset = DEFAULT_TEMPLATE[executionMode] ?? 'cautious';
    const ids = presetMap[defaultPreset] ?? [];
    if (ids.length > 0) {
      setConfigState({ builtinRuleIds: ids, customRules: [] });
      setTemplateId(defaultPreset);
    }
  }

  const enabledSet = useMemo(
    () => new Set(config.builtinRuleIds),
    [config.builtinRuleIds],
  );

  const groupedRules = useMemo<GroupedCategory[]>(() => {
    return CATEGORY_META.map((cat) => {
      const rules = builtinRules
        .filter((r) => r.category === cat.key)
        .sort((a, b) => (SEVERITY_ORDER[a.severity] ?? 9) - (SEVERITY_ORDER[b.severity] ?? 9))
        .map((r) => ({ ...r, enabled: enabledSet.has(r.id) }));
      return {
        key: cat.key,
        label: cat.label,
        iconName: cat.iconName,
        rules,
        enabledCount: rules.filter((r) => r.enabled).length,
      };
    });
  }, [builtinRules, enabledSet]);

  const applyTemplate = useCallback(
    (id: Exclude<RuleTemplateId, 'custom'>) => {
      const ids = presetMap[id] ?? [];
      setConfigState((prev) => ({ ...prev, builtinRuleIds: ids }));
      setTemplateId(id);
    },
    [presetMap],
  );

  const toggleRule = useCallback((ruleId: string, enabled: boolean) => {
    setConfigState((prev) => {
      const ids = enabled
        ? [...prev.builtinRuleIds, ruleId]
        : prev.builtinRuleIds.filter((id) => id !== ruleId);
      return { ...prev, builtinRuleIds: ids };
    });
    setTemplateId('custom');
  }, []);

  const addCustomRule = useCallback((text: string, severity: CustomRuleSeverity = 'SHOULD') => {
    const trimmed = text.trim();
    if (!trimmed) return;
    setConfigState((prev) => ({
      ...prev,
      customRules: [...prev.customRules, { severity, text: trimmed }],
    }));
  }, []);

  const removeCustomRule = useCallback((index: number) => {
    setConfigState((prev) => ({
      ...prev,
      customRules: prev.customRules.filter((_, i) => i !== index),
    }));
  }, []);

  const updateCustomRule = useCallback((index: number, text: string, severity?: CustomRuleSeverity) => {
    setConfigState((prev) => ({
      ...prev,
      customRules: prev.customRules.map((r, i) => (
        i === index ? { severity: severity ?? r.severity, text: text.trim() } : r
      )),
    }));
  }, []);

  const setConfig = useCallback((newConfig: BehaviorRuleConfig) => {
    setConfigState(newConfig);
    setTemplateId('custom');
  }, []);

  return {
    builtinRules,
    config,
    templateId,
    groupedRules,
    applyTemplate,
    toggleRule,
    addCustomRule,
    removeCustomRule,
    updateCustomRule,
    setConfig,
    isLoading,
  };
}
