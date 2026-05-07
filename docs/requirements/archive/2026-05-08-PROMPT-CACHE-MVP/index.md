# Prompt Cache MVP

---
id: PROMPT-CACHE-MVP
mode: full
status: done
priority: P1
risk: Mid-Full
created: 2026-05-07
updated: 2026-05-08
---

## 摘要

给 SkillForge 5 个 LLM provider 启用 prompt cache 全链路支持：稳定 system prompt + tools 序列化 prefix（让 server 自动 cache 命中率最大化）+ 给 Anthropic Claude 加手动 `cache_control` 标记（必须手动，否则永远不 cache）+ 5 provider 完整解析 cache usage 字段 normalize 成统一指标 + OBS-1 dashboard 显示 cache hit rate / read tokens / creation tokens。

**预期收益**：每次 LLM 调用节省 50-90% input tokens（Claude 从 0% 自动 cache → 手动 cache，DeepSeek / Qwen / OpenAI / xiaomi-mimo 已自动 cache 但 SkillForge 当前看不到 usage）。

## 阅读顺序

1. [MRD](mrd.md) — 用户原始诉求 + 5 provider 协议差异 + 参考实现（claude code / openclaw）
2. [PRD](prd.md) — 4 阶段 V1 范围 + 验收点 + V1/V2 拆分
3. [技术方案](tech-design.md) — 架构 / INV / 5 provider normalize / cache break 检测算法 / 测试策略

## V1 范围

- **Phase 1**: 稳定 prefix（boundary marker + tools normalize + per-tool hash 漂移检测）— 5 provider 都受益
- **Phase 2**: ClaudeProvider 加手动 cache_control（3 breakpoint：system 末 + tools 末 + 最后 user message 末，参考 claude code）
- **Phase 3**: 5 provider Usage 解析 normalize（统一字段 cacheRead / cacheCreation；按 protocol family 分支）
- **Phase 4**: Trace + cache break 检测 + dashboard 显示 cache_read / creation / hit rate（不显示 cost 美刀）

**V2 推迟**：Qwen 手动 cache_control（自动 cache 已 work，guaranteed hit 是 polish）/ Anthropic 1h TTL / Messages 历史 sliding window cache / cost 美刀估算

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
