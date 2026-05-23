# OPT-REPORT-V1 — 优化报告链路（new flywheel route）

> 创建：2026-05-22
> 状态：active
> 模式：Full pipeline（涉及 schema + 新 agent + 跨栈 + 新 trigger 路径）
> 跟现有链路关系：**并行存在**，不替换。未来某版考虑合并/二选一。

## 背景反思

旧 "Run Opt Loop" 链路是 **cluster-based auto pipeline**：

```
annotator → cluster(≥3 members) → dispatcher(4 filter) → curator → A/B → promote
```

每层都有 gate（≥3 members / surface allowlist / 24h cooldown / ACTIVE_STAGES），适合"数据足够、强信号、自动闭环"场景。但在 dogfood 早期：
- 单 agent 7 天可能只有 6 个 session，凑不出 3 个同签名的 cluster
- 失败模式多样化（success/failure/partial × skill/prompt/unclear × ReadFile/Bash/...），分散到各分桶
- 各 gate 累积过滤→几乎所有触发都返回 ∅

→ **没有 actionable 输出**，只看到"链路通了"但拿不到价值。

## 新链路设计（OPT-REPORT）

```
operator 点 "Generate Report" 按钮
  ↓
report-generator agent 启动
  ↓
① 拉取目标 agent 7d 内所有 production session（不限 cap=10）
  ↓
② SubAgent 并行 fan-out：每个子任务处理一批 session 的标注
   每批：DetectSignal(skip) + GetTrace + SpanBehaviorStats + LLM 判断 outcome/surface
  ↓
③ Barrier：等所有子任务返回，汇总全部标注
  ↓
④ 归因分析 LLM：吃所有标注 + 行为统计 → 产出结构化报告
   - 摘要：success rate / failure 主类型 / 行为效率
   - 主要问题 top 3-5（带 session 例证）
   - 优化建议（按 surface 分类 + 优先级）
  ↓
⑤ 持久化到 t_opt_report：markdown 内容 + structured summary JSON
  ↓
⑥ FE Reports 页面列出历史报告，operator 阅读后决定如何迭代
```

## 跟现有飞轮关系（并行存在）

| 维度 | 旧链路（Run Opt Loop） | 新链路（Generate Report） |
|---|---|---|
| 触发 | "Run Opt Loop" 按钮 | "Generate Report" 按钮 |
| 标注范围 | DetectSignal flagged + cap 10 | 全部 7d session |
| 处理方式 | 顺序串行 | SubAgent fan-out 并行 |
| 输出形态 | OptimizationEvent (auto proposal) | Markdown 报告 + summary JSON |
| 闭环方式 | 自动 A/B → canary → promote | 人工决策 |
| 适用场景 | 高频同类失败、数据成熟 | 早期数据、稀疏失败、需要观察 |

合并方向（V2 backlog）：报告里"高置信度优化建议"自动进入旧链路 curator/A/B，低置信度仍由 operator 审。

## V1 MVP 范围（含 fan-out）

### 必做（V1.0）

**架构（两 agent 分层）**：
- **`report-generator`**（parent，model: claude-sonnet-4 或同档）—— 编排者，调度 + 归因
- **`session-batch-annotator`**（child，model: 同上）—— 干活者，标注一批 session

**数据**：
1. V97 migration：
   - 新表 `t_opt_report`（reportId / agentId / windowStart / windowEnd / contentMd / summaryJson / status / errorReason / createdAt）
   - 新表 `t_opt_report_batch`（batchId / reportId / subAgentSessionId / sessionIdsJson / status / annotationsWrittenCount）—— 跟踪每个 SubAgent 批次进度
   - 内联 seed 两个 system agent 的 system_prompt

**BE Tool**：
2. 新 `LoadSessionBatch(agentId, windowDays, offset, limit)` —— 拉 session 元数据 + 已有标注（report-generator 用）
3. 新 `WriteOptReport(reportId, contentMd, summaryJson)` —— 写报告表（report-generator 用）
4. 新 `RecordBatchAnnotations(reportId, sessionIds[])` —— SubAgent 完成后回写 batch 状态
5. 复用现有 Tool：`SubAgent` / `GetTrace` / `SpanBehaviorStats` / `AnnotateSession`

**BE Service / Controller**：
6. 新 endpoint `POST /api/flywheel/agents/{agentId}/generate-report?windowDays=7`
7. 创建 `t_opt_report` 行（status=pending）+ 启动 report-generator 跟 `Run Opt Loop` 同款 chatAsync 模式
8. WS 广播 `opt_report_completed` 事件（沿用 FLYWHEEL-CHAIN-VISIBILITY Gap A 通知机制）

**Report-generator prompt 逻辑**（顺序）：
```
STEP 1: LoadSessionBatch(agentId, windowDays=7, offset=0, limit=200)
        → 拿到 N 个 session 列表
STEP 2: 拆批（batchSize=5）→ 算出 ceil(N/5) 个批次
STEP 3: 对每批 dispatch 一个 SubAgent(session-batch-annotator)，
        task 中显式传 session ID 列表
        ←单 turn 内并行 dispatch 多个 SubAgent（LLM 支持 parallel tool_use）
STEP 4: 等所有 SubAgent 完成（每个返回 RecordBatchAnnotations 确认）
STEP 5: LoadSessionBatch 再读一遍（这次拿到所有新标注）
STEP 6: LLM 归因分析：基于全部 annotation + behavior stats
        → 生成 markdown 报告 + summary JSON
STEP 7: WriteOptReport（status=completed）
```

**Session-batch-annotator prompt 逻辑**：
```
入参：task message 含 reportId + sessionIds[]
STEP A: 对每个 sessionId：
   GetTrace(list+detail) → SpanBehaviorStats → LLM 判断 outcome/surface
   → AnnotateSession 写标注
STEP B: 完成后 RecordBatchAnnotations(reportId, sessionIds[]) 确认 batch 完成
```

**FE**：
9. AgentDrawer 加新按钮 "Generate Report"（紫色 secondary，跟 "Run Opt Loop" 同行）
10. Insights 加新 tab "Reports" 列历史报告（agentId / windowStart-End / status / createdAt + 行点击展开）
11. 报告详情面板：左侧 markdown 渲染 + 右侧 summary JSON 折叠显示
12. WS 监听 `opt_report_completed` → antd notification 同款（沿用 Gap A 机制）

### V1.0 不做（留 V1.1+）
- ❌ 报告版本对比（v1 → v2 diff）
- ❌ 报告自动 cron trigger（先 manual button）
- ❌ 报告 → 旧链路自动 dispatch（先纯人工读）
- ❌ summary JSON schema 固化（V1 just LLM free-form）
- ❌ SubAgent 失败重试（V1 失败就标记 batch=error 不阻塞报告生成）

### 并行设计要点

**Batch size = 5**：单个 SubAgent 处理 5 个 session，~30s。如果 7d 有 50 session，10 个 SubAgent 并行，总耗时 ~30-60s。

**Barrier 实现**：靠 SubAgent 同步返回机制——report-generator 在一个 turn 内 dispatch 所有 SubAgent，下一个 turn 收到所有 tool_result 后继续。

**失败隔离**：单个 SubAgent 异常时记 `t_opt_report_batch.status=error`，主流程仍继续做归因（基于已成功批次）。报告 markdown 显示 "X / Y 批次成功"。

## 验收

V1.0：
- 点 "Generate Report" → BE 启动 report-generator session → ~1-2 分钟后报告写入 t_opt_report
- FE 报告页能看到这个报告，markdown 渲染正常
- 报告内容真有信息量（不是空模板填的）

## 已知 follow-up

- V1.1：SubAgent fan-out 并行（≥20 session 时单线性太慢）
- V1.2：报告 schema 固化 + 优化建议结构化（不只 markdown，让 FE 能挑出"建议项"）
- V2：合并入口——把报告里"高置信度+skill/prompt surface 明确"的建议自动进 curator
- 反思项（独立讨论）：agent 各能力开发是不是都该 MVP-first，不直接到终态

## 渐进式开发反思

旧链路一上来就追求"全自动闭环"（V1 cluster → V2 canary → V3 attribution → V5/V6 A/B + promote）。听起来终态完美，但每层都需要前层产出高质量数据，dogfood 早期数据稀疏时直接全链路空跑。

教训：
- 先做"读"层（observability + 标注 + 报告），让人能看到 agent 在干什么
- 再做"写"层（自动提案 + A/B + promote），有数据驱动了才闭环
- 各能力的演进应该**先有人工 baseline**，再考虑自动化
