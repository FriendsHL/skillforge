/**
 * UI metadata for behavior rule categories.
 * Rule text comes from the backend API — this file only has display config.
 */

export interface CategoryMeta {
  key: string;
  label: string;
  iconName: string;
  sortOrder: number;
}

export const CATEGORY_META: CategoryMeta[] = [
  { key: 'safety', label: 'Safety & Security', iconName: 'SafetyCertificateOutlined', sortOrder: 0 },
  { key: 'quality', label: 'Code Quality', iconName: 'CodeOutlined', sortOrder: 1 },
  { key: 'workflow', label: 'Workflow', iconName: 'ThunderboltOutlined', sortOrder: 2 },
  { key: 'communication', label: 'Communication', iconName: 'MessageOutlined', sortOrder: 3 },
];

export const SEVERITY_ORDER: Record<string, number> = {
  must: 0,
  should: 1,
  may: 2,
};

export type RuleTemplateId = 'autonomous' | 'cautious' | 'full' | 'custom';

export interface RuleTemplate {
  label: string;
  description: string;
}

export const RULE_TEMPLATES: Record<Exclude<RuleTemplateId, 'custom'>, RuleTemplate> = {
  autonomous: { label: 'Autonomous', description: 'Safety + core quality (~6 rules)' },
  cautious: { label: 'Cautious', description: 'Safety + quality + workflow + communication (~12 rules)' },
  full: { label: 'Full', description: 'All built-in rules enabled' },
};

export const DEFAULT_TEMPLATE: Record<string, Exclude<RuleTemplateId, 'custom'>> = {
  ask: 'cautious',
  auto: 'autonomous',
};
