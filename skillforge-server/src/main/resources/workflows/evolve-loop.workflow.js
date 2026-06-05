export const meta = {
  name: 'evolve-loop',
  description: 'AUTOEVOLVE 确定性进化循环 DSL 版: (report) → 写死排序 issue → 逐 issue { 候选叶子(agent) → GetCandidateDiff → TriggerAbEval → 确定性轮询 GetAbResult → 写死阈值 keep → RecordIteration } → carry-forward → adopt(人定夺/autoApprove)。编排全在 JS(无 maxLoops/无漂移)，LLM 只做候选生成叶子。',
  phases: [
    { title: 'Report', detail: '取/跑 opt-report，拿 topIssues' },
    { title: 'Evolve', detail: '逐 issue 生成候选 + A/B + 确定性判断 + 落账' },
    { title: 'Adopt', detail: 'autoApprove 直通 / 否则人工 gate' }
  ]
}

// 候选叶子的严格输出 schema。candidateId 来自 GenerateCandidate service 持久化后的
// 真实返回(叶子只转发，伪造不了)；prediction 可选(无 scenario id 时留空数组)。
var CAND_SCHEMA = {
  type: 'object',
  required: ['candidateId', 'surface', 'changeDesc'],
  properties: {
    candidateId: { type: 'string' },
    surface: { type: 'string' },
    changeDesc: { type: 'string' },
    prediction: { type: 'object' }
  }
}

// Phase 1: 优化面 = prompt（设计 §6「不做」清单里 bundle/多面推后）。候选生成 +
// GetCandidateDiff + 落账 label 都用它。
var SURFACE = 'prompt'

// A/B 评测走 agent-surface 引擎（单指针 prompt bundle）。**为什么不是 prompt-surface**：
// 只有 agent-surface A/B(t_agent_evolve_ab_run) 存 per-scenario flip，ReconcilePrediction
// 才能算 G3 对账(hits/misses/confidence) —— prompt-surface A/B 喂不进 ReconcilePrediction。
// 这与 orchestrator 路一致(它也用 agent-surface A/B 才能产 reconciliation)。
var AB_SURFACE = 'agent'

// ── 确定性阈值 (复用 GetAbResult.wouldPromote 作种子, 设计 R2) ──
// wouldPromote(prompt) = deltaPassRate >= 15pp。keep 直接采用它，避免另立一套阈值。
function decideKeep(res) {
  return !!(res && res.wouldPromote === true)
}

// ── 写死的 issue 选择 + 排序 (Phase 1; 设计 §6 不用 agent 叶子) ──
// 只取 convertible 的 issue；优先 prompt 相关面；按 severity×recurrence×confidence 降序。
function selectAndRank(topIssues) {
  var sevWeight = { high: 3, medium: 2, low: 1 }
  var convertible = []
  for (var i = 0; i < topIssues.length; i++) {
    var it = topIssues[i]
    if (it && it.convertible === true) {
      var sev = sevWeight[it.severity] || 1
      var rec = (typeof it.recurrence === 'number' && it.recurrence > 0) ? it.recurrence : 1
      var conf = (typeof it.confidence === 'number' && it.confidence > 0) ? it.confidence : 0.5
      var promptRelevant = it.surface === 'prompt' || it.fixSurface === 'prompt' || it.suspectSurface === 'prompt'
      convertible.push({ issue: it, score: sev * rec * conf, promptRelevant: promptRelevant })
    }
  }
  // 优先 prompt 相关；若一个都没有则退回全部 convertible(避免空转)。
  var primary = convertible.filter(function (e) { return e.promptRelevant })
  var pool = primary.length > 0 ? primary : convertible
  pool.sort(function (a, b) { return b.score - a.score })
  return pool.map(function (e) { return e.issue })
}

var targetAgentId = args.targetAgentId
var maxIter = args.maxIter || 10
var autoApprove = args.autoApprove === true
var agentIdStr = '' + targetAgentId

// ── Report ──
phase('Report')
var reportId = args.reportId
if (!reportId) {
  // 退路 B1: 没现成报告时跑 opt-report 子流程(阻塞等完成)，拿 reportId。
  var sub = tool('RunOptReportSubflow', { agentId: targetAgentId })
  reportId = sub.reportId
}
var report = tool('GetOptReport', { reportId: reportId, expectedAgentId: agentIdStr })
var topIssues = (report && report.topIssues) ? report.topIssues : []
var issues = selectAndRank(topIssues)
log('evolve-loop reportId=' + reportId + ' topIssues=' + topIssues.length + ' rankedConvertible=' + issues.length)

if (issues.length === 0) {
  return { status: 'empty', reportId: reportId, evaluated: 0, kept: 0, trajectory: [] }
}

// ── Evolve (确定性循环, 无 maxLoops/无漂移) ──
phase('Evolve')
// carry-forward 状态: best 是「当前最优」prompt 版本 + 它的分数。
var best = { promptVersionId: null, score: null }
var keptList = []
var trajectory = []
var priorChange = null
var priorEvalReport = null
var iter = 0

for (var k = 0; k < issues.length && iter < maxIter; k++) {
  var issue = issues[k]
  iter++

  // 🟨 候选生成叶子(LLM, 唯一非确定性节点)。issue + 当前 best + 反思上下文 → 候选。
  var basePromptVersionId = best.promptVersionId   // iter1 为 null
  var issueDesc = ctx.json({
    title: issue.title,
    severity: issue.severity,
    friction: issue.friction,
    rootCause: issue.rootCause,
    proposedFix: issue.proposedFix,
    suggestion: issue.suggestion
  })
  var candPrompt = 'targetAgentId=' + targetAgentId + ' surface=' + SURFACE
    + ' reportId=' + reportId + ' issueId=' + issue.id
    + '\nissue=' + issueDesc
    + (basePromptVersionId ? '\nbasePromptVersionId=' + basePromptVersionId : '')
    + (priorChange ? '\npriorChange=' + priorChange : '')
    + (priorEvalReport ? '\npriorEvalReport=' + priorEvalReport : '')
    + '\n按你的 system_prompt 调一次 GenerateCandidate 产候选, 只输出 {candidateId, surface, changeDesc, prediction} 严格 JSON。'
  var cand = agent(candPrompt, { agentSlug: 'evolve-candidate-gen', schema: CAND_SCHEMA, phase: 'Evolve' })
  if (!cand || !cand.candidateId) {
    log('iter ' + iter + ' issue=' + issue.id + ' produced no candidate; skipping')
    continue
  }

  // 🟩 语义 delta(确定性, JS 侧组装; 设计 §3.2 路径 a)。
  var diff = tool('GetCandidateDiff', {
    candidateId: cand.candidateId, surface: SURFACE, baseVersionId: basePromptVersionId
  })
  var semanticDelta = {
    surface: SURFACE,
    before: diff ? diff.before : null,
    after: diff ? diff.after : null,
    diff: diff ? diff.diff : null,
    changeDesc: cand.changeDesc
  }

  // 🟩 A/B(agent-surface, 单指针 prompt bundle)。candidate = 新候选 prompt; baseline =
  // 当前 best(iter1 为 active = 空 bundle), 每轮 candidate vs current-best 爬坡。
  var candidateBundle = { promptVersionId: cand.candidateId }
  var baselineBundle = best.promptVersionId ? { promptVersionId: best.promptVersionId } : {}
  var ab = tool('TriggerAbEval', {
    surface: AB_SURFACE,
    candidateBundle: candidateBundle,
    baselineBundle: baselineBundle,
    targetAgentId: agentIdStr,
    evolveRunId: ctx.runId()
  })
  var abRunId = ab ? ab.abRunId : null

  // 🟦 确定性轮询(非 agent 轮询): GetAbResult 内部阻塞 ~90s, 仍 running 就再调。
  var res = null
  if (abRunId) {
    res = tool('GetAbResult', { surface: AB_SURFACE, abRunId: abRunId, targetAgentId: agentIdStr })
    while (res && res.status === 'running') {
      res = tool('GetAbResult', { surface: AB_SURFACE, abRunId: abRunId, targetAgentId: agentIdStr })
    }
  }

  // 🟩 G3 对账(设计 §2 节点图): 把候选叶子 staked 的 prediction 跟 A/B 实际 per-scenario
  // flip 对账 → reconciliation{hits,misses,riskHits,surprises,confidence}。这是 orchestrator
  // 路的核心特性("改配置+预期结果"对账), workflow 路必须等价。
  var reconciliation = null
  if (abRunId && cand.prediction) {
    var recon = tool('ReconcilePrediction', {
      prediction: cand.prediction,
      abRunId: abRunId,
      targetAgentId: agentIdStr
    })
    // 正常返回是 {issueId,targetProblem,hits,...,confidence}; 仅 ownership 不符时为
    // {status:'rejected'} —— 那种不当 reconciliation 记。
    if (recon && recon.status !== 'rejected') {
      reconciliation = recon
    }
  }

  // 🟦 确定性 keep 判断(写死阈值, 复用 wouldPromote 种子)。
  var keptThis = decideKeep(res)
  var delta = res ? res.delta : null
  var baselineScore = res ? res.baselineScore : null
  var candidateScore = res ? res.candidateScore : null

  // 🟩 落账(含 semanticDelta + prediction + reconciliation sidecar, 供 read API 追溯)。
  tool('RecordIteration', {
    evolveRunId: ctx.runId(),
    iteration: iter,
    surface: SURFACE,
    changeDesc: cand.changeDesc,
    candidateId: cand.candidateId,
    baselineScore: baselineScore,
    candidateScore: candidateScore,
    delta: delta,
    kept: keptThis,
    abRunId: abRunId,
    prediction: cand.prediction,
    reconciliation: reconciliation,
    semanticDelta: semanticDelta,
    candidateBundle: { promptVersionId: cand.candidateId }
  })

  trajectory.push({ iteration: iter, candidateId: cand.candidateId, delta: delta, kept: keptThis })
  log('iter ' + iter + ' issue=' + issue.id + ' candidate=' + cand.candidateId
    + ' delta=' + delta + ' kept=' + keptThis)

  // 🟦 carry-forward: 赢家推进 best; 否则守住 best。
  priorChange = cand.changeDesc
  priorEvalReport = ctx.json({ delta: delta, baselineScore: baselineScore, candidateScore: candidateScore, kept: keptThis })
  if (keptThis) {
    keptList.push({ iteration: iter, candidateId: cand.candidateId, promptVersionId: cand.candidateId, delta: delta })
    best = { promptVersionId: cand.candidateId, score: candidateScore }
  }
}

// ── Adopt (Iron-Law 人类闸门: 实际 adopt 仍走人类 POST /runs/{id}/adopt) ──
phase('Adopt')
var summary = {
  status: 'completed',
  reportId: reportId,
  evaluated: iter,
  kept: keptList.length,
  keptCandidates: keptList,
  best: best,
  trajectory: trajectory
}
if (autoApprove) {
  return summary
}
// 非 autoApprove(人触发): 末尾人工 gate 看 kept 候选 + 轨迹。
// ⚠️ P1 限制(设计 §7 R3): EvolveController.runViaWorkflow 硬编码 autoApprove=true,
// 所以这条分支当前走不到。humanApprove → pause → resume 在"轮询中途暂停再恢复"下的
// journal-replay 未测(R3 把 resume 复杂度推后到 P2)。真要开人工 gate 前必须先验
// resume-mid-polling。
return humanApprove(summary)
