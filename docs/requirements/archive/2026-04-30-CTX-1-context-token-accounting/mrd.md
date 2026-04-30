---
id: CTX-1
type: mrd
created: 2026-04-30
updated: 2026-04-30
---

# CTX-1 MRD — 三档触发接全量估算 + 阈值配置化 + 撞窗 retry

## 用户原始诉求

todo.md(2026-04-30 草拟):

> 区分累计 token budget 与当前 context pressure;补全 preflight 估算 system/messages/tools/prompt,并优化超窗 compact/retry。

2026-04-30 与用户对齐时,用户的白话理解(确认对齐):

> 我们触发压缩的方法有问题。之前只算了 messages,但是没有连带 system prompt(含 memory)、tools schema 那些块,导致可能会超过 LLM 单次请求的 context window 然后报错。

## 痛点(三个)

1. **三档 compact 触发的估算只算 messages,真实请求会撞窗**
   - 现状:`AgentLoopEngine` 三档触发(B1>0.60 light、B2>0.80 full、preemptive>0.85 full)的分子是 `TokenEstimator.estimate(messages)`,只覆盖对话历史
   - 漏掉:system prompt(含 memory 注入)、tools schema、max_tokens(output 预留)
   - 后果:估算说"还在 60% 以下不用 compact",实际请求送出去早就超 context window,LLM 直接拒(413 / context_length_exceeded)

2. **三档阈值是硬编码常量,不能 per-provider 调**
   - 现状:0.60 / 0.80 / 0.85 写在 `AgentLoopEngine` 里
   - 不同 provider 的 chat template overhead 不同,实际 ratio 增量不一样,固定阈值不灵活

3. **撞窗后没有自动补救**
   - 现状:`chatStream` 单次尝试不重试(known footgun #3 的本意是避免 SocketTimeout 重发 delta)
   - 撞 context_length_exceeded 直接抛上游 → 用户看到"对话失败"
   - 实际可以单次 full compact + retry 一次,UX 大幅改善

## 已存在的能力(2026-04-30 勘查发现)

> 第一次起 PRD 时漏勘了这些,导致设计臃肿。重写 PRD 时全部砍掉。

| 能力 | 位置 | 说明 |
|---|---|---|
| Session 级累计 input/output tokens | `SessionEntity.totalInputTokens` / `totalOutputTokens` | `SessionService.java:507-508` 每次更新 messages 时累加 |
| Cumulative tokens dashboard 展示 | `RightRail.tsx` cumulativeTokens / `SessionList.tsx` lifetimeTokens / `SessionStatsBar.tsx` | 已有 |
| 完整 preflight 估算算法 | `ContextBreakdownService.breakdown()` (server) | 覆盖 system prompt + tools schema + messages,REST 暴露 `/sessions/{id}/context-breakdown` |
| Per-LLM-call usage 落库 | `TraceSpanEntity.inputTokens` / `outputTokens` (OBS-1 交付) | per call 粒度 |

**关键 gap**:`ContextBreakdownService.breakdown()` 这套完整估算**只接到 dashboard**,**没接到** `AgentLoopEngine` 的三档 compact 触发。两条估算路径分裂,触发用的是简陋版,dashboard 看到的是完整版,数字不一致而且触发会失准。

## 限制 / 已确认的非目标

- ❌ **不做 partial compact**:那是 P9-4/P9-5 的范围
- ❌ **不动 SessionEntity 数据流**:totalInputTokens / totalOutputTokens 已存在,本 wave 不改
- ❌ **不动 dashboard 展示逻辑**:cumulativeTokens / lifetimeTokens 已展示,本 wave 不动
- ❌ **不新增 schema / migration**:本 wave 纯代码改动,无 V38

## 已对齐的方向(2026-04-30 讨论后)

详见 [index.md 待决策点](index.md#待决策点讨论用) + 后续重写后的 prd.md。本 MRD 不重复。

## 仍需 PRD 进一步明确

- **F1 估算逻辑共享方式**:把 ContextBreakdownService 的算法抽到 core 工具方法,让 engine 和 dashboard 都调,还是只在 core 加一个独立方法、ContextBreakdownService 保持现状?(tech-design 决)
- **撞窗 retry 失败的错误体验**:retry 一次还失败时,前端展示"内容过长"还是普通 error
- **F2 阈值校准**:实测一次后是否同步更新默认值
