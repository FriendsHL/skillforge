# OPT-REPORT-V1 — 优化报告链路（new flywheel route）

> 创建：2026-05-22
> 状态：done / archived（V1.0–V1.3，commit `e3fde131` / `f50d91b8`；后续自动化另立需求）
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
- V1.2 ✅：报告 schema 固化 + 优化建议结构化（不只 markdown，让 FE 能挑出"建议项"）+ "Convert to Event" 桥接
- V2：合并入口——把报告里"高置信度+skill/prompt surface 明确"的建议自动进 curator
- 反思项（独立讨论）：agent 各能力开发是不是都该 MVP-first，不直接到终态

---

## V1.2 — 桥接报告 issue 到 OptimizationEvent（2026-05-23）

V1.0/V1.1 完成"读"层（生成可观察报告）后，痛点：**报告生成完了 issue 全是 markdown 散文，operator 看完没有 1-click 入路径到旧 A/B 流水线**。

V1.2 范围：固化 `summary_json.topIssues` schema → 让 FE 能渲染每条 issue 的"Convert to Event" 按钮 → operator 手动 convert 后走原有 attribution → A/B 流水线。

**不动**旧 attribution-curator / dispatcher（V2 长期方案再合并入口）。

### 1. `summary_json.topIssues` schema 固化

报告生成器 LLM 输出 summary_json 时**必须严格按下面 schema**：

```json
{
  "topIssues": [
    {
      "id": "issue-1",                            // 稳定 ID，建议 "issue-1" / "issue-2" 风格
      "title": "...",                              // 必填
      "severity": "high" | "medium" | "low",       // 必填
      "sessionCount": 3,                           // 必填，≥1
      "exampleSessionIds": ["sess-abc", "sess-def"], // 必填，≥1 个
      "suspectSurface": "skill" | "prompt" | "behavior_rule" | "other" | "unclear",
      "confidence": 0.85,                          // 必填，0.0 - 1.0
      "suggestion": "...",                         // 必填
      "expectedImpact": "..."                      // 选填
    }
  ]
}
```

V1.0/V1.1 的"自由结构 + 建议"语气改成"严格 schema"约束（prompt update via V102 migration）。

**Java 侧**：
- `OptReportIssueDto` record（package `com.skillforge.server.optreport.dto`）
- `OptReportSummaryJson` record 包含 `List<OptReportIssueDto> topIssues`
- `OptReportSummaryParser`：`parse(String summaryJson)` 返 `OptReportSummaryJson` 或抛 `IllegalArgumentException`（enum / 必填 / 范围 / 一致性校验）

### 2. V101 migration

```sql
ALTER TABLE t_optimization_event
  ADD COLUMN source_report_id VARCHAR(36) REFERENCES t_opt_report(id) ON DELETE SET NULL,
  ADD COLUMN source_issue_id  VARCHAR(64);  -- issue.id e.g. "issue-1"
```

加 partial index 反向查找：

```sql
CREATE INDEX idx_opt_event_source_report ON t_optimization_event(source_report_id)
  WHERE source_report_id IS NOT NULL;
```

**同时**`ALTER COLUMN pattern_id DROP NOT NULL` —— 报告衍生的 OptEvent 没有 pattern（pattern 是旧链路 cluster 概念），patternId nullable 反向兼容（已有行全有值）。entity field 同时从 `long` 改 `Long`（实际已经是 `Long` —— 见 OptimizationEventEntity.java line 110）。

**已知限制**：报告衍生 OptEvent 当 operator 在 dashboard 上点"approve"时，`AttributionApprovalService` 调 `SkillDraftService.createDraftFromAttribution(... patternId, ...)` 会因为 patternId=null 抛 `IllegalArgumentException`。V1.2 接受这个限制 — V2 合并入口时一并解决（候选方案：建 "virtual report-pattern" 行或扩展 createDraftFromAttribution 接受 null patternId）。

### 3. New service + endpoint

**`OptReportToEventBridge`**（同 package `com.skillforge.server.optreport`）：

```java
@Transactional
public OptimizationEventEntity convertIssueToEvent(String reportId, String issueId)
```

逻辑：
1. load report → `IllegalStateException` 若 status != completed
2. parse summary_json → `NoSuchElementException` 若 issue 找不到
3. 检查幂等：query `WHERE source_report_id=? AND source_issue_id=?` → 若已存在直接返已有 entity
4. issue.suspectSurface ∈ {other, unclear} → `IllegalArgumentException` "operator 不能自动转 / 自己写 OptEvent"
5. INSERT 新 OptimizationEventEntity：
   - `agentId` ← report.agentId
   - `patternId` ← null
   - `surfaceType` ← issue.suspectSurface
   - `changeType` ← "from_opt_report"（identify 来源）
   - `stage` ← "proposal_pending"
   - `description` ← title + "\n\n" + suggestion + 若 expectedImpact 加 "\n\nExpected: " + expectedImpact
   - `expectedImpact` ← issue.expectedImpact（独立列；可空）
   - `confidence` ← issue.confidence（BigDecimal 转 scale 2）
   - `risk` ← derive from severity: high→high, medium→medium, low→low
   - `sourceReportId` ← reportId
   - `sourceIssueId` ← issueId
   - `cooldownExpiresAt` ← now + 24h
6. return entity

**Endpoint**: `POST /api/flywheel/reports/{reportId}/issues/{issueId}/convert-to-event`

- 200 + `{eventId, alreadyConverted: false}` 新建
- 200 + `{eventId, alreadyConverted: true}` 已存在
- 400 若 issue.suspectSurface ∈ {other, unclear} 或 summary_json schema 不合法
- 400 若 report 状态非 completed
- 404 若 report 或 issue id 不存在

### 4. `GET /api/flywheel/reports/{reportId}` 加 alreadyConverted

不再原样回 `summaryJson` raw string —— 解析 topIssues 后每个 issue 加 `alreadyConverted: boolean`（基于 `findBySourceReportIdAndSourceIssueId`）让 FE disable 已转按钮。

向后兼容：raw `summaryJson` 字段保留（FE 旧版本仍可读），只是新加 `topIssuesEnriched: [...]` array 单独承载这个 enriched 视图。

### 5. 测试

- `OptReportSummaryParserTest`（5+ case：合法 / 缺字段 / enum 错 / confidence 越界 / 空 array / 多 issue）
- `OptReportToEventBridgeTest`（happy / 'other' surface 拒掉 / idempotent re-convert / report not completed / issue not found / non-completed report）
- `OptReportControllerTest` 加 convert endpoint case（200 新建 / 200 already / 400 invalid surface / 404 report / 404 issue）

### 验收

V1.2：
- POST `/api/flywheel/reports/{id}/issues/{iid}/convert-to-event` → 200 + 新 OptEvent 行
- Repeat 同样 call → 200 + alreadyConverted=true
- `GET /reports/{id}` 含 topIssuesEnriched + 每个 issue 的 alreadyConverted 标志
- 报告 prompt V102 update 后 LLM 输出严格 schema
- mvn test BUILD SUCCESS


## 渐进式开发反思

旧链路一上来就追求"全自动闭环"（V1 cluster → V2 canary → V3 attribution → V5/V6 A/B + promote）。听起来终态完美，但每层都需要前层产出高质量数据，dogfood 早期数据稀疏时直接全链路空跑。

教训：
- 先做"读"层（observability + 标注 + 报告），让人能看到 agent 在干什么
- 再做"写"层（自动提案 + A/B + promote），有数据驱动了才闭环
- 各能力的演进应该**先有人工 baseline**，再考虑自动化
