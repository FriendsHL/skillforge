---
id: CTX-1
type: prd
status: delivered
created: 2026-04-30
updated: 2026-04-30
delivered: 2026-04-30
---

# CTX-1 PRD — 三档触发接全量估算 + 阈值配置化 + 撞窗 retry

> 2026-04-30 重写。原版基于不完整勘查设计了 6 个功能(F1-F6),后发现 F3/F6 已存在、F1 算法已存在,scope 大幅缩水到 3 个功能。

## 目标(三件事)

1. **G1 接线** —— `AgentLoopEngine` 三档 compact 触发的 ratio 分子改用完整估算(system + messages + tools + max_tokens),与 dashboard `ContextBreakdownService` 共享算法,不再维护两条估算路径
2. **G2 阈值配置化** —— 三档触发阈值移到 `LlmProperties.providers[].compactThresholds`,可 per-provider 调
3. **G3 撞窗 retry** —— `chatStream` 撞 context_length_exceeded 时自动 full compact + retry 一次,失败才上抛

## 非目标

- ❌ Partial compact / 恢复(P9-4/P9-5)
- ❌ 改 SessionEntity / Flyway migration / 新增 schema 字段
- ❌ 改 dashboard 已有的 cumulativeTokens / lifetimeTokens / context-breakdown UI(已存在)
- ❌ 改 light/full compact 算法本身(只改触发条件)
- ❌ Token-aware quota / rate limiting(后续需求)

## 工作流(用户视角)

CTX-1 改动**对终端用户基本透明**,差异:

1. **触发更准** —— 之前估算偏低,该 compact 时没 compact;改完后真到达"60%/80%/85% 真实占比"才触发,该压时压、不该压时不压
2. **不再"对话失败"** —— 超长对话遇到 context_length_exceeded 时自动恢复,用户感知是"加载稍慢"而不是"对话挂掉"
3. **配置可调** —— 不同 provider 可以配不同阈值(运维可见,不暴露给终端用户)

Dashboard 上 `RightRail.tsx` 的"context window usage breakdown"展示**不变**,因为它已经走的是完整估算 ([已存在能力](mrd.md#已存在的能力2026-04-30-勘查发现));只是 engine 三档触发现在与 dashboard 看到的数字一致了。

## 功能需求

### F1 — 三档触发接到完整估算(对应 G1)

**FR-1.1** 在 `skillforge-core` 提供共享估算工具(具体形式 [tech-design](tech-design.md) 决):

- 输入:LlmRequest(已含 systemPrompt + messages + tools + maxTokens)
- 输出:估算 token 数,覆盖 system prompt + messages + tools schema + max_tokens
- 与 `ContextBreakdownService` 内部使用同一算法(避免数字漂移)

**FR-1.2** `AgentLoopEngine` 三档触发的 ratio 分子改用 FR-1.1 的工具:

- B1 engine-soft light(>softRatio):分子从 `TokenEstimator.estimate(messages)` 改为完整估算
- B2 engine-hard full(>hardRatio,B1-gated):同上
- Preemptive full(>preemptiveRatio):同上

**FR-1.3** 重排逻辑:三档判断目前在 `LlmRequest` 构建之前,需要把 request 构建上移,或把 LlmRequest 当 builder 边构建边判(详见 tech-design)

**FR-1.4** 保留 `TokenEstimator.estimate(messages)` 给 compact 算法层(`LightCompactStrategy` / `FullCompactStrategy` 的 reclaim 计算用,不动)

### F2 — 三档阈值配置化(对应 G2)

**FR-2.1** `LlmProperties.providers[]` 加可选嵌套对象:

```yaml
llm:
  providers:
    claude:
      compactThresholds:
        softRatio: 0.60       # B1 light 触发(默认 0.60)
        hardRatio: 0.80       # B2 full 触发,必须 B1 已跑(默认 0.80)
        preemptiveRatio: 0.85 # preemptive full 触发(默认 0.85)
```

未配置时使用默认值(现状阈值)。

**FR-2.2** 三档阈值实现完成后,**用真实数据实测一次**校准(本 wave 内做):跑一个长会话(50+ 轮),记录"估算值 vs LlmResponse.usage 实测值"的偏差。如偏差 >15% 调整默认值。

### F3 — chatStream 撞窗 retry(对应 G3)

**FR-3.1** 加新异常类型 `LlmContextLengthExceededException`(在 `skillforge-core/llm`):

- `ClaudeProvider`:识别 `error.type == "invalid_request_error"` + message 含 "prompt is too long" 时抛此异常
- `OpenAiProvider`:识别 `error.code == "context_length_exceeded"` 或 message 含 "context length" 时抛此异常
- 其他错误保持原有处理路径不变

**FR-3.2** `AgentLoopEngine` 在 chatStream 调用处 catch `LlmContextLengthExceededException`:

- 抓到后:**单次** `compactorCallback.compactFull(sessionId, messages, "post-overflow", reason)`
- compact 成功后用压缩后的 messages 重发 `chatStream`(单次 retry)
- 重发还失败 → 上抛
- 不递归(同一 iteration 内最多一次 retry)

**FR-3.3** retry 路径不与现有 `SocketTimeoutException` 重试逻辑混用:Socket 超时是 transport 层(provider 内 retry),context_length_exceeded 是协议层(engine 层 compact + retry),两者职责分离

**FR-3.4** retry 失败 log error,前端收到 error 状态时附带提示:"对话历史过长且自动压缩仍未恢复,请新建会话"(具体文案 dashboard 改动里定,本 wave 不动 UI 文案)

## 验收标准

| ID | 验收点 | 验证方式 |
|---|---|---|
| AC-1 | F1 实现后,AgentLoopEngine 三档触发的 ratio 分子等于"完整估算",与 ContextBreakdownService.breakdown() 算出的 total 数字一致(±0,因为共享算法) | 集成测试构造一个 session,分别从两条路径取数,assert 相等 |
| AC-2 | F1 共享估算覆盖 system prompt + messages + tools + maxTokens 四块,assert 所有四块都计入 | 单元测试覆盖各分量 |
| AC-3 | F2 配置生效:YAML 配置 0.50/0.70/0.80,实际触发阈值用配置值;不配置时用默认 0.60/0.80/0.85 | 集成测试 |
| AC-4 | F3 mock provider 第一次抛 LlmContextLengthExceededException,第二次正常,engine 应自动 compactFull + retry,最终成功 | 集成测试用 fake provider |
| AC-5 | F3 mock provider 两次都抛 LlmContextLengthExceededException,engine 应只 retry 一次,然后上抛 | 集成测试断言 retry 次数 = 1 |
| AC-6 | F3 错误识别:Claude / OpenAI 各自的 context_length 错误都能被 LlmContextLengthExceededException 捕获 | 单元测试覆盖各 provider 错误响应解析 |
| AC-7 | 阈值校准:实现完成后跑一次长会话(50+ 轮),log 估算 vs 实测偏差,偏差 >15% 则调整默认阈值并写进 delivery-index.md | Phase Final 手动跑 + 看日志 |

## 验证预期

- **单元测试**:共享估算工具(各分量)、provider 错误识别、retry 次数限制
- **集成测试**:覆盖 AC-1 ~ AC-6,用 fake provider 注入特定行为
- **真实任务验证**(Phase Final):跑一个 50+ 轮的实际会话,log 比对估算 vs 实测;刻意构造接近 window 上限的会话(memory + 大 tool result + 长 history),触发撞窗 retry 路径,看是否成功恢复
- **回归**:`CompactionServiceTest` / `AgentLoopEngineCompactTest` / `CompactPersistenceIT` 全部通过

## 优先级

| 优先级 | 范围 |
|---|---|
| **P0(必做)** | F1, F2, F3 |
| **P1(本 wave 强烈建议)** | AC-7 阈值校准 |
| **P2(切出去)** | TraceSpan 加 estimatedRequestTokens 字段(原 PRD 的 F4)— 校准用 log 即可,持久化校准数据是 follow-up 需求 |

## Pipeline 档位

[原计划 Full,2026-04-30 用户授权降为 Mid](tech-design.md#pipeline-档位说明) — 改动小、纯接线 + 配置化 + 单点 retry,不动 schema 不动 SessionEntity 数据流;但仍触碰核心文件清单(AgentLoopEngine、LlmProvider),严格按规则应是 Full,**Mid 是用户授权降级,不是按规则降**。
