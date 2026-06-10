import api from './client';

// Dashboard API
export const getDashboardOverview = () => api.get('/dashboard/overview');
export const getDailyUsage = (days = 30) => api.get(`/dashboard/usage/daily?days=${days}`);
export const getUsageByModel = () => api.get('/dashboard/usage/by-model');
export const getUsageByAgent = () => api.get('/dashboard/usage/by-agent');

/**
 * SKILL-DASHBOARD-POLISH-V2 §G — aggregated skill-area stats for the Dashboard
 * SkillSummaryCard. Computed BE-side from the existing repos so the FE renders
 * 5 numbers in one round-trip instead of N. All fields are non-negative ints;
 * absent counts arrive as `0` (BE coerces nulls so the card's "—" empty state
 * is reserved purely for the load-failure path).
 */
export interface SkillDashboardSummary {
  /** A/B promoted runs created in the last 7d via the system path
   *  (triggeredByUserId == 0 = scheduled cron). */
  autoUpgradedThisWeek: number;
  /** Drafts in `status='draft'` that still need operator review. */
  pendingDraftsCount: number;
  /** SkillEvolutionRun rows with `status='FAILED'` created in the last 7d. */
  failedEvolveThisWeek: number;
  /** All skills with `enabled=true` (system + custom). */
  totalEnabledSkills: number;
  /** Skills whose latest `t_skill_eval_history.composite_score` < 60. */
  lowScoreSkillsCount: number;
}

export const getDashboardSkillSummary = (userId: number) =>
  api.get<SkillDashboardSummary>('/dashboard/skill-summary', { params: { userId } });
