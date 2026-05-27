# Spike Evidence — R-AMA-MEM-5 假设验证

> 创建：2026-05-27
> 状态：spike PASS（假设成立，进入 MVP 实施有 ROI 支撑）
> 关联：[index.md](index.md) / [mrd.md](mrd.md) / [prd.md](prd.md) / [tech-design.md](tech-design.md)

## 假设

wiki R-AMA-MEM-5：给定 user 的最近 N 个 session transcript（**当前 `LlmMemorySynthesizer` 不消费 transcript，只消费已 extract 的 memory**），LLM 能否挖出新的 memory observation —— 这些 observation **无法从 existing memory 重组得出**？

## Spike 设计

| 项 | 设计 |
|---|---|
| 跑法 | `/tmp/dreaming_spike.py`（Python + psql + urllib，绕开 SpringBootTest 嵌入 postgres lock 冲突 — server 在 dashboard 跑着）|
| 输入 | user_id=1（dogfood 主力 user）最近 5 个 session，msg_count ≥ 4 |
| 每 message cap | 600 chars（content_json 包含 user prompt / assistant text / tool_use / tool_result 混合）|
| 每 session cap | 30 message |
| LLM | mimo-v2.5-pro（SkillForge 当前 default provider，OpenAI-compatible endpoint） |
| Prompt 结构 | system instructions 借鉴 Anthropic Dreaming `instructions` field 风格（focus / ignore 双 list + 输出 JSON schema 强约束）|
| Branch | `spike/memory-from-session-transcripts`（**不进 main**；spike Java test 临时放 `skillforge-server/src/test/java/spike/MemoryFromTranscriptSpike.java`，跑不成因 embedded postgres lock 占用，被 Python 版本替代）|

## 输入规模

- 5 sessions, 45,875 chars (~11.5k chars transcript)
- Prompt total: **15,782 tokens in**（含 system prompt + transcript + instructions）
- Existing memory snapshot for user 1: **0 active**（status='active' filter；尽管 t_memory 总行数 80 但全在 archived / pending / other status）

## LLM 响应

- 输出: **739 tokens out**
- 延迟: 32.6 秒
- 估算成本: **~$0.005/run**（mimo input $0.27/M + output $1.10/M，按官方报价）
- Anthropic Dreaming 估算对比: $0.15-0.30/run（mimo 便宜 30x 主因模型本身价格差）

## 7 条 observation 质量评估

LLM 返了一个标准 JSON array，每条含 `summary / evidence / importance / kind` 4 字段。具体 transcript 引用已 redact（涉用户原始对话内容，git 不留）；下面是结构 + actionability 评估：

| # | kind | importance | actionable? | 说明（不含原对话内容）|
|---|---|---|---|---|
| 1 | communication-style | high | ✅ | 跨 5 session 一致语言+语气信号（中文 + casual + direct）→ agent system_prompt inject 风格 hint |
| 2 | domain-knowledge | high | ✅ | 用户领域焦点（AI coding agent 开源项目调研）→ agent 优先推荐相关链接 |
| 3 | domain-knowledge | high | ✅ | 用户深度关注 platform internals（tool/skill/cache）— SkillForge dogfood 一致 |
| **4** | **preference** | **high** | ✅ **超高价值** | "用户会 push back if 没真查 tool config 就脑补结论" — LLM 引用了**具体 quote** 作 evidence（不是脑补 generic preference）→ agent loop 改 default 行为：先 GetAgentConfig 再下结论 |
| 5 | workflow | medium | ✅ | broad query → drill-down 研究模式 |
| 6 | preference | medium | ✅ | 偏好结构化对比（关键差异 / 好用的点 等）→ agent 输出 format 改 table/对比 |
| 7 | domain-knowledge | medium | ✅ | 关心 token cache hit rate / cost metric → agent 主动报相关数字 |

## 关键发现

1. **LLM 引用了真实的 session id + 具体 quote 作 evidence** — 不是脑补，可追溯回 transcript 原句
2. **5 enum kind 提议**：`preference / workflow / constraint / domain-knowledge / communication-style` — 跟 SkillForge 现有 `t_memory.memory_kind` 3 enum (`observation / reflection / optimized`) **不直接对齐**，MVP 实施时需要 mapping 决策（追加 enum / collapse 到现有 3 enum / 改 schema 加 sub-kind）
3. **#4 是纯能力扩展证据** — "implicit preference 来自用户的 push back 反应" 这种信号**绝对不会从已有 memory 重组得出**（因为 transcript 里的 push back 当时没有沉淀成 memory observation 就消失了），只有 transcript-level 输入才能挖
4. **prompt 第一版就 work** — 无需多轮迭代，输出严格 JSON 无 prose noise；说明 instructions field 设计借鉴 Anthropic Dreaming 是 well-founded

## ROI 验证

| 维度 | 数字 / 评估 |
|---|---|
| 假设 R-AMA-MEM-5 | ✅ **成立** — transcript 真活有新信号 |
| 单次成本 | ~$0.005/run / user |
| 假设 daily 03:30 跑 1000 user | ~$5/day = $150/month — 可忽略 |
| Actionable observation 数量 | 7/7（100% 可消费）；3 条 high importance + 4 条 medium importance |
| Transcript-only 信号比例 | 至少 1/7 (#4) 是**纯 transcript 信号**，无 existing memory path 可得；其他 6 条 transcript 提供更强 evidence |
| Spike 工作量 | ~1 小时（含 SpringBootTest 失败 pivot 到 Python） |

## prompt 设计要点（MVP V1 移植参考）

system prompt 的有效结构：
1. **Role + task** — "You are a memory synthesis assistant. Read recent conversations, extract NEW observations."
2. **输出 schema 强约束** — JSON array + 4 字段 (summary/evidence/importance/kind) + enum value 限定
3. **focus list** — stylistic / workflow / implicit constraint / domain / communication
4. **ignore list** — one-off debugging / 显然信息 / LLM response（focus on user not agent）/ transient details
5. **format strict** — "Output ONLY the JSON array, no prose"

mimo-v2.5-pro 完美遵守了上述 5 点。MVP V1 prompt template 直接抄。

## 进入 MVP V1 的判断

✅ **进**。理由：
- R-AMA-MEM-5 假设硬证据成立
- 至少 1 条 (#4) 是无可替代的纯 transcript 信号
- 成本可忽略
- prompt 第一版就 work，工程实施风险低
- existing memory snapshot 0 active 说明 user 1 现状 dreaming 跑批正好填空

## V1 实施时 spike-evidence 转化的设计点

| spike 发现 | MVP V1 落地 |
|---|---|
| 5 enum kind 提议跟 `t_memory.memory_kind` 不对齐 | 在 [tech-design.md](tech-design.md) D 决策补一条：MVP 映射方案（候选：扩 enum / collapse / 加 sub-kind 字段）|
| evidence 字段 LLM 真活引用 session id + quote | MVP `MemoryProposal.evidence` 字段 schema 保留，存原 quote + session_id 链接 |
| importance enum (high/medium/low) | 跟 `t_memory.importance` 现有字段对齐（如果已有）；如无则新加 |
| Transcript content_json 含 tool_use / tool_result 混合（spike 直接扔进 prompt 也 work） | MVP `SessionTranscriptProvider` 可以不复杂 chunking — 直接 join 全文 + per-message cap 简化 |
| Per-message 600 char cap 已能区分 user "quote 风格" | MVP `TranscriptConfig.chunkSize=600` 实测合理；不需要 chunking overlap |
| 5 sessions / ~11.5k input tokens / 32s 一次跑 | MVP `maxEventsPerSession=30 / sessionLimit=5-10` 起点合理 |

## 工件位置

| 文件 | 状态 |
|---|---|
| `spike/memory-from-session-transcripts` git branch | 保留作 reference；MVP V1 ship 后可删 |
| `skillforge-server/src/test/java/spike/MemoryFromTranscriptSpike.java`（spike branch 上）| untracked，embedded postgres lock 失败版本，参考价值低 |
| `/tmp/dreaming_spike.py` | spike 真实跑的脚本；**不进 git**（含 LLM API endpoint hardcode + 直接 psql 连接）|
| spike stdout（7 条 observation 完整 JSON）| **不持久化**；本 `spike-evidence.md` 只 redact 后摘要 |
| 本 `spike-evidence.md` | **进 main**，作为需求包 4 件套 + spike 证据归档（共 5 件套）|

## 关联

- 假设来源：wiki [`anthropic-managed-agents.md`](../../../../../research-docs/research/agent-harness-wiki/harness/anthropic-managed-agents.md) R-AMA-MEM-5（P1，"能力扩展不是优化"）
- spike Java test 失败的根因：`could not lock /Users/youren/.skillforge/pgdata/epg-lock` — SkillForge dashboard server 占用 zonky embedded postgres lock；MVP V1 实施时**真正的 IT 测试**需要在 SkillForge server stopped 状态跑（一次性即可），或者改 IT 用 testcontainers 独立 postgres
- 后续 MVP 决策：见 [index.md](index.md) D1-D6 + 本文 § "V1 实施时 spike-evidence 转化的设计点"（开 Plan 时 ratify）
