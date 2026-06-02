export const meta = {
  name: 'opt-report',
  description: 'OPT-REPORT 优化报告 DSL 版: load → fanout annotate → aggregate(归因) → 人工 approve。等价于 report-generator 的 7 步编排，确定性步骤放 JS、LLM 工作放 agent()。',
  phases: [
    { title: 'Load', detail: '拉取候选 production session 列表' },
    { title: 'Annotate', detail: '按批 fanout session-batch-annotator 标注' },
    { title: 'Aggregate', detail: '重新拉取 + 读目标配置 + LLM 归因产出 summaryJson' },
    { title: 'Approve', detail: '人工审批 attribution summary' }
  ]
}

// schema 只加在"agent 本就输出严格 JSON"的步骤上 (Load 的 loader / Aggregate 的
// aggregator)。Annotate 复用现成 session-batch-annotator —— 它写库 (AnnotateSession
// → t_session_annotation, 按 sessionId) 并返回 prose, 不产 JSON, 标注可见性靠
// Aggregate 重新 LoadSessionBatch 从 DB 读回 (不靠 annotate 的返回值), 所以这里
// 故意不加 schema (加了会逼一个 prose agent 输出 JSON, 3 次重试后必失败)。
var LOAD_SCHEMA = {
  type: 'object',
  required: ['total', 'items'],
  properties: {
    total: { type: 'integer' },
    items: {
      type: 'array',
      items: { type: 'object', required: ['sessionId'], properties: { sessionId: { type: 'string' } } }
    }
  }
}

// W1: failureCount 必含。W2: contentMd 必含 (aggregator prompt 标 必填, schema 不锁
// 的话 LLM 漏了也过 → FE 报告 tab 空白 markdown)。topIssues item 列必填核心字段,
// 但 additionalProperties 不锁 (LLM 会补 expectedImpact / targetRuleText 等)。
var SUMMARY_SCHEMA = {
  type: 'object',
  required: ['totalSessions', 'successCount', 'failureCount', 'successRate', 'topIssues', 'batchesTotal', 'batchesSucceeded', 'contentMd'],
  properties: {
    totalSessions: { type: 'integer' },
    successCount: { type: 'integer' },
    failureCount: { type: 'integer' },
    successRate: { type: 'number' },
    batchesTotal: { type: 'integer' },
    batchesSucceeded: { type: 'integer' },
    contentMd: { type: 'string' },
    topIssues: {
      type: 'array',
      items: {
        type: 'object',
        required: ['id', 'title', 'severity', 'sessionCount', 'exampleSessionIds', 'suspectSurface', 'confidence', 'suggestion', 'actionType'],
        properties: {
          id: { type: 'string' },
          title: { type: 'string' },
          severity: { type: 'string', enum: ['high', 'medium', 'low'] },
          sessionCount: { type: 'integer' },
          exampleSessionIds: { type: 'array', items: { type: 'string' } },
          suspectSurface: { type: 'string', enum: ['skill', 'prompt', 'behavior_rule', 'other', 'unclear'] },
          fixSurface: { type: 'string', enum: ['skill', 'prompt', 'behavior_rule', 'other', 'unclear'] },
          confidence: { type: 'number' },
          suggestion: { type: 'string' },
          actionType: { type: 'string', enum: ['new', 'modify', 'duplicate'] }
        }
      }
    }
  }
}

var agentId = args.agentId
// dogfood data 稀疏：默认窗口拉到 30 天，否则近 7 天常常 0-few session、evolve 空转
// （callers 仍可显式传 args.windowDays 覆盖）。
var windowDays = args.windowDays || 30
log('opt-report agentId=' + agentId + ' windowDays=' + windowDays)

// ── Load (= report-generator STEP 1) ──
phase('Load')
var loaded = agent(
  'Load sessions: agentId=' + agentId + ' windowDays=' + windowDays
    + '。调一次 LoadSessionBatch(agentId, windowDays, offset=0, limit=200) 并返回 {total, items:[{sessionId}]} 的严格 JSON。',
  { agentSlug: 'opt-report-orchestrator', schema: LOAD_SCHEMA, phase: 'Load' })

if (!loaded || !loaded.items || loaded.items.length === 0) {
  log('no candidate sessions; returning empty')
  return { status: 'empty', totalSessions: 0 }
}

// ── 拆批 (= STEP 2, 纯 JS 确定性, 等价 ceil(total/5)) ──
var batches = []
for (var i = 0; i < loaded.items.length; i += 5) {
  var slice = loaded.items.slice(i, i + 5)
  var sids = slice.map(function (s) { return s.sessionId })
  batches.push(sids)
}
log('split ' + loaded.items.length + ' sessions into ' + batches.length + ' batches of <=5')

// ── Annotate (= STEP 3+4, parallel barrier) ──
phase('Annotate')
var runId = ctx.runId()
var annotations = parallel(batches.map(function (sids, idx) {
  return function () {
    return agent(
      '标注一批 production session。reportId=' + runId + ' batchId=' + runId + '-b' + idx
        + ' sessionIds=' + ctx.json(sids)
        + '。按你 system_prompt 的 STEP A-B 跑完: 逐个 session 取 trace + 行为统计 + LLM 标注 (AnnotateSession), 最后 RecordBatchAnnotations 回写。',
      { agentSlug: 'session-batch-annotator', phase: 'Annotate' })
  }
}))
var okBatches = annotations.filter(function (a) { return a !== null }).length
log('annotated ' + okBatches + '/' + batches.length + ' batches ok')

// ── Aggregate (= STEP 5+5.5+6, reload + config + 归因) ──
phase('Aggregate')
var summary = agent(
  'Aggregate agentId=' + agentId + ' windowDays=' + windowDays
    + '。重新调 LoadSessionBatch 拿带标注的 items, 调 GetAgentConfig(targetAgentId=' + agentId + ') 拿目标配置, '
    + '产出 summaryJson (严格 schema, 含 failureCount, topIssues[] 引用真实 sessionId, 每 issue 判 actionType)。'
    // W3: batchesTotal / batchesSucceeded 是 workflow-level 状态, aggregator 无 tool 拿不到,
    // 不传它只能瞎猜 (通常乐观填 batchesTotal)。这里塞 JS 算好的真实值, 让它直接照填。
    + ' batchesTotal=' + batches.length + ' batchesSucceeded=' + okBatches
    + ' (这两个值已由 workflow 算好, summaryJson 的 batchesTotal/batchesSucceeded 直接用这两个数, 不要自己猜)。'
    + '只输出 summaryJson 严格 JSON。',
  { agentSlug: 'opt-report-aggregator', schema: SUMMARY_SCHEMA, phase: 'Aggregate' })

// ── Approve (= 新增人工 gate, dsl-syntax §11 / prd FR-7.1) ──
// AUTOEVOLVE-AGENT-FLYWHEEL: the agent-driven evolve orchestrator passes
// args.autoApprove=true — the report is just an INPUT to the autonomous loop (the
// human gate moves to the END of the evolve loop, where the operator adopts the
// trajectory), so pausing here on humanApprove would deadlock the orchestrator (it
// would wait for a report that never completes). A human-triggered run (no
// autoApprove) keeps the manual gate.
phase('Approve')
var decision = args.autoApprove
  ? { approved: true, reviewerId: 'system:auto', reason: 'auto-approved (orchestrator-driven evolve loop)' }
  : humanApprove(summary)

return {
  status: decision && decision.approved ? 'approved' : 'rejected',
  summary: summary,
  reviewerId: decision ? decision.reviewerId : null,
  reason: decision ? decision.reason : null,
  batchesTotal: batches.length,
  batchesAnnotated: okBatches
}
