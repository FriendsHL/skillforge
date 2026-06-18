# Tech-Design（草案）— ACP-EXTERNAL-AGENT

> 状态：草案（未排期）。真做要 Full + Plan 对抗循环；本文是方向草图，非定稿。

## Track A — ACP client runner

### 组件
1. **AcpClient**（新）：JSON-RPC over stdio 客户端；启动/管理 cc/codex adapter 子进程；`initialize` / `authenticate` / `session/new` / `session/prompt` / `session/cancel` / `session/setModel`；处理 incoming `session/update` 通知 + 权限请求。
2. **AcpAgentRunner**（新）：把一次 cc/codex 任务跑成一个 SkillForge **子 session**（复用 SubAgent 的 parent_session_id / 结果回推模型）。父 agent 经一个工具（如 `RunExternalAgent`）派活。
3. **AcpUpdateTranslator**（新）：`session/update` → SkillForge `Message/ContentBlock`：
   - agent_message_chunk → text block（WS 节流渲染）
   - reasoning/thought → reasoning block（复用 reasoning_content）
   - tool_call / tool_call_update → tool_use + tool_result（**必须凑合法配对**，见不变量）
   - plan → TodoWrite 式展示；token → usage
   - **规范化层**：cc adapter vs codex adapter 字段差异 → 归一（同 ProviderProtocolFamily 套路）
4. **权限桥**：ACP permission request ↔ SkillForge ask/confirmation（pending-confirmation registry）。策略可配：逐个弹 / 自动批 + 高危弹。
5. **结果回投**：子 session 完成 → 复用 **CHANNEL-ASYNC-DELIVERY** 把最终结果投回原渠道。

### 落进现有积木
Message·ContentBlock / WS 流式 + 节流 / SubAgent 子 session / ask-confirmation / 异步投递 —— 大多复用。新写主要是 AcpClient + Translator + 权限桥 + 子进程/auth 管理。

### 子 agent 计数
cc 派子 agent = Task `tool_call` → Translator 产生一个 tool_use(Task) 节点 → session 里数 Task = 子 agent 数。（内部细节看不到，见 Track B。）

## Track B — OTEL-NATIVE-TRACING（观测基座）

### 目标
SkillForge 观测层说**真 OTLP**，以便：① 原生接 cc/codex OTel；② 经 TRACEPARENT 把 cc 整树嵌进 SkillForge session trace；③ 与标准 OTel 生态互通。

### 路径已拍：B3 转真 OTel（2026-06-19，用户）
引入 OpenTelemetry Java SDK，SkillForge span 用通用 OTel span（gen_ai 语义约定）；OTLP receiver 接外部 cc/codex；trace 视图读 OTel store；现有 LlmSpan 作为 gen_ai span 子集迁移。最干净、最大改动、平台级基座。

**B3 必须分阶段**（自身就是一个迁移工程，**独立成 `OTEL-NATIVE-TRACING` 包**）：
1. 加 OTel Java SDK + SkillForge instrumentation 产 OTel span（**双写** OTel + 现有 LlmSpan，读路径先不动）
2. 迁读路径：TraceTreeService / TracesController / dashboard trace 视图读 OTel store
3. ⚠️ **迁 evolve/eval 消费方**：`TraceScenarioImportService` 等把 trace 当评测/收割数据源——必须一起迁，否则自进化链路断（B3 最易低估点）
4. OTLP receiver + TRACEPARENT 注入（接 cc/codex 外部 OTel，嵌进统一树）
5. 下线自定义 LlmSpan（或保留为 OTel 之上的 LLM 专用视图）

（曾考虑 B1 适配层 / B2 纯双写不迁读——B1 仍非真 OTel、语义有损；B2 是 B3 的第一步。用户选 B3 终态。）

### 关键机制
- **OTLP receiver**：gRPC 4317 / HTTP 4318（或内嵌 OTel Collector）。
- **TRACEPARENT 注入**：AcpAgentRunner 启动 cc 时设 `CLAUDE_CODE_ENABLE_TELEMETRY=1` + OTLP endpoint 指向 SkillForge + 注入本次 session 的 TRACEPARENT → cc（含子 agent）span 自动嵌入。
- **trace 树视图**：现有 TraceTreeService 扩展读通用 OTel span；子 agent span 按 `is_subagent`/`parent_tool_use_id` 归位。
- **内容治理**：默认结构-only；内容（prompt/输出）opt-in + 受限存储/保留。

## tool_use ↔ tool_result 不变量（必守）
cc 的 tool_call 翻译进 SkillForge 持久化路径，必须满足 tool_use↔tool_result 配对 + 持久化-engine 字节一致不变量（见 persistence-shape-invariant / 核心文件清单）。若不入 engine messages、仅展示 → 用展示专用变体，不碰持久化对账。**Plan 阶段必须显式定这条边界。**

## 分期建议
- **P1（Track A 最小闭环）**：AcpClient + cc 单 agent + Translator(text/tool_call/reasoning) + 权限桥 + 子 session 渲染 + 结果回投。验收 1-4。先不接 OTel 深度。
- **P2（codex）**：codex adapter 跑通；规范化层覆盖双 adapter 差异。
- **P3（Track B 观测深度）**：OTLP receiver + TRACEPARENT 注入 + trace 树下钻子 agent。验收 5。OTel-native 迁移路径（B1/B2/B3）Plan 单独决策——大概率独立成 `OTEL-NATIVE-TRACING` 包。

## 开放问题（Plan / 用户决策）
- ~~OQ-1：Track B 走 B1/B2/B3 哪条？~~ **已拍 B3 转真 OTel（2026-06-19）**，且 Track B 独立成 `OTEL-NATIVE-TRACING` 包、自身分 5 阶段（见上，注意迁 evolve/eval 消费方）。
- OQ-2：cc/codex adapter 子进程在 SkillForge 部署形态（本机 / 云端）怎么打包管理？
- OQ-3：权限默认策略（逐个弹 vs 自动批+高危弹）？
- OQ-4：cc 跑成独立子 session（可单独查看）还是内联父 session 流？
- OQ-5：内容捕获默认关，按需开——谁有权开 + 存哪？
