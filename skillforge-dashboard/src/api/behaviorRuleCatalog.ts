import api from './client';

// ─── Behavior Rules ─────────────────────────────────────────────────────────

export interface BehaviorRule {
  id: string;
  category: string;
  severity: 'must' | 'should' | 'may';
  label: string;
  labelZh: string;
  deprecated: boolean;
  replacedBy: string | null;
  presets: string[];
}

export interface BehaviorRulesResponse {
  version: string;
  rules: BehaviorRule[];
}

export interface BehaviorRulePresetResponse {
  presetName: string;
  ruleIds: string[];
}

export type CustomRuleSeverity = 'MUST' | 'SHOULD' | 'MAY';

export interface CustomBehaviorRule {
  severity: CustomRuleSeverity;
  text: string;
}

export interface BehaviorRuleConfig {
  builtinRuleIds: string[];
  customRules: CustomBehaviorRule[];
}

export const getBehaviorRules = () =>
  api.get<BehaviorRulesResponse>('/behavior-rules');

export const getBehaviorRulesPreset = (executionMode: string) =>
  api.get<BehaviorRulePresetResponse>('/behavior-rules/presets', {
    params: { executionMode },
  });
