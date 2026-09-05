import { describe, expect, it } from 'vitest';
import { FALLBACK_MODEL_OPTIONS } from '../models';

describe('Bailian Token Plan fallback capabilities', () => {
  it('preserves Qwen 3.8 vision while limiting the other Token Plan models to text', () => {
    const models = FALLBACK_MODEL_OPTIONS.filter((model) => model.provider === 'bailian-token-plan');
    expect(models.map((model) => model.model)).toEqual([
      'qwen3.8-max', 'qwen3.7-max', 'deepseek-v4-pro', 'deepseek-v4-pro-0813', 'deepseek-v4-flash-0731',
    ]);
    expect(models.filter((model) => model.supportsVision).map((model) => model.model)).toEqual(['qwen3.8-max']);
    expect(models.every((model) => model.supportsThinking)).toBe(true);
    expect(models.filter((model) => model.supportsReasoningEffort).map((model) => model.model)).toEqual([
      'deepseek-v4-pro', 'deepseek-v4-pro-0813', 'deepseek-v4-flash-0731',
    ]);
  });
});
