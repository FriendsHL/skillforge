export const meta = {
  name: 'evolve-loop',
  description: 'AUTOEVOLVE 确定性进化循环 DSL 版: (report) → 写死排序 issue → 逐 issue { 候选叶子(跨面 bundle) → 多面 GetCandidateDiff → TriggerAbEval(agent) → 确定性轮询 GetAbResult → 写死阈值 keep → RecordIteration } → carry-forward bundle → adopt(人定夺/autoApprove)。编排全在 JS(无 maxLoops/无漂移)，LLM 只做候选生成叶子。',
  phases: [
    { title: 'Report', detail: '取/跑 opt-report，拿 topIssues' },
    { title: 'Evolve', detail: '逐 issue 生成跨面候选 bundle + A/B + 确定性判断 + 落账' },
    { title: 'Adopt', detail: 'autoApprove 直通 / 否则人工 gate' }
  ]
}

// 候选叶子的严格输出 schema (Phase 2a 多面)。candidateBundle 是真 id 三件套(各面
// candidateId 来自 GenerateCandidate service 持久化后的真实返回，叶子只转发，伪造不了)；
// surfaces 是叶子声明实际改的面(JS 侧以 candidateBundle 非空指针为权威重算，见
// normalizeSurfaces)；prediction 可选(无 scenario id 时留空数组)。
var CAND_SCHEMA = {
  type: 'object',
  required: ['candidateBundle', 'surfaces', 'changeDesc'],
  properties: {
    candidateBundle: { type: 'object' },
    surfaces: { type: 'array', items: { type: 'string' } },
    changeDesc: { type: 'string' },
    prediction: { type: 'object' }
  }
}

// 三个可生成的优化面 (agent 是 A/B 路由元面，不是可生成面)。canonical 顺序固定，
// 用于 bundle 指针归一、whitelist 过滤、semanticDelta 顺序。
var SURFACES = ['prompt', 'behavior_rule', 'skill']

// A/B 评测走 agent-surface 引擎 (整三件套 bundle)。**为什么不是各面单独 A/B**：
// 只有 agent-surface A/B(t_agent_evolve_ab_run) 存 per-scenario flip，ReconcilePrediction
// 才能算 G3 对账(hits/misses/confidence)，且一个 bundle 一次触发(不按面计 budget)。
// 这与 orchestrator 路一致(它也用 agent-surface A/B 才能产 reconciliation)。
var AB_SURFACE = 'agent'

// ── 确定性阈值 (复用 GetAbResult.wouldPromote 作种子) ──
// wouldPromote = deltaPassRate >= 15pp。keep 直接采用它，避免另立一套阈值。
function decideKeep(res) {
  return !!(res && res.wouldPromote === true)
}

function nonBlank(s) {
  return s != null && ('' + s).length > 0
}

// ── 面选择 = Hybrid (设计 §1) ──
// JS 从 issue 的 fixSurface(单值或数组) 算确定性白名单 allowedSurfaces；候选叶子只能在
// 这个子集内决定实际改哪几面(可减面，不能越界 —— 越界由 filterToAllowed 兜)。无 fixSurface
// 退回 ['prompt'](等价 Phase 1)。回退: fixSurface 归因差就放宽算法(改这一个函数)。
function resolveAllowedSurfaces(issue) {
  var raw = issue ? issue.fixSurface : null
  var list = []
  if (Array.isArray(raw)) {
    list = raw
  } else if (typeof raw === 'string' && raw) {
    list = [raw]
  }
  var allowed = []
  for (var i = 0; i < SURFACES.length; i++) {
    if (list.indexOf(SURFACES[i]) !== -1) allowed.push(SURFACES[i])
  }
  if (allowed.length === 0) allowed = ['prompt']   // 退化 Phase 1 等价
  return allowed
}

// 取某个面在 bundle 里的指针 id (null = 该面用 active / 未改)。
function bundlePointer(bundle, surface) {
  if (!bundle) return null
  if (surface === 'prompt') return nonBlank(bundle.promptVersionId) ? bundle.promptVersionId : null
  if (surface === 'behavior_rule') return nonBlank(bundle.behaviorRuleVersionId) ? bundle.behaviorRuleVersionId : null
  if (surface === 'skill') return nonBlank(bundle.skillDraftId) ? bundle.skillDraftId : null
  return null
}

// 以 bundle 非空指针为权威重算实际改的面 (canonical 顺序)。防叶子声明的 surfaces 与
// candidateBundle 不一致 (设计 R5: 以 bundle 为准，记 warn 不 fail)。
function normalizeSurfaces(bundle) {
  var out = []
  for (var i = 0; i < SURFACES.length; i++) {
    if (bundlePointer(bundle, SURFACES[i]) != null) out.push(SURFACES[i])
  }
  return out
}

// RecordIteration 的 candidateId 必填且单值: 取 bundle 主指针 (prompt > behavior_rule > skill)。
function primaryPointer(bundle) {
  return bundlePointer(bundle, 'prompt')
    || bundlePointer(bundle, 'behavior_rule')
    || bundlePointer(bundle, 'skill')
    || null
}

// whitelist 兜底: 丢掉 allowedSurfaces 之外的任何面指针 (叶子越界保护，确定性，不信 LLM)。
// 被丢的面/指针累加到 dropped 数组里，由调用方记日志(便于生产诊断"叶子越界改了哪个面")。
function filterToAllowed(bundle, allowed, dropped) {
  var out = {}
  for (var i = 0; i < SURFACES.length; i++) {
    var s = SURFACES[i]
    var p = bundlePointer(bundle, s)
    if (p == null) continue
    if (allowed.indexOf(s) !== -1) {
      if (s === 'prompt') out.promptVersionId = p
      else if (s === 'behavior_rule') out.behaviorRuleVersionId = p
      else if (s === 'skill') out.skillDraftId = p
    } else if (dropped) {
      dropped.push({ surface: s, pointer: p })
    }
  }
  return out
}

// 各面独立 carry-forward: mergedBundle = best.bundle 叠加本轮改的面 (changed 覆盖 base)。
// = "各面当前最优组合" —— 未改的面沿用上轮赢家指针，改的面用本轮新候选。
function mergeBundle(base, changed) {
  var out = {}
  for (var i = 0; i < SURFACES.length; i++) {
    var s = SURFACES[i]
    var p = bundlePointer(changed, s)
    if (p == null) p = bundlePointer(base, s)
    if (p != null) {
      if (s === 'prompt') out.promptVersionId = p
      else if (s === 'behavior_rule') out.behaviorRuleVersionId = p
      else if (s === 'skill') out.skillDraftId = p
    }
  }
  return out
}

// ── 写死的 issue 选择 + 排序 (设计 §6 不用 agent 叶子；§4 去 prompt 独宠) ──
// 只取 convertible 的 issue；按 severity×recurrence×confidence 降序 (不再 prompt 独宠 ——
// 面选择交给 per-issue 的 hybrid 白名单)。
function selectAndRank(topIssues) {
  var sevWeight = { high: 3, medium: 2, low: 1 }
  var convertible = []
  for (var i = 0; i < topIssues.length; i++) {
    var it = topIssues[i]
    if (it && it.convertible === true) {
      var sev = sevWeight[it.severity] || 1
      var rec = (typeof it.recurrence === 'number' && it.recurrence > 0) ? it.recurrence : 1
      var conf = (typeof it.confidence === 'number' && it.confidence > 0) ? it.confidence : 0.5
      convertible.push({ issue: it, score: sev * rec * conf })
    }
  }
  convertible.sort(function (a, b) { return b.score - a.score })
  return convertible.map(function (e) { return e.issue })
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
// carry-forward 状态: best 是「各面当前最优组合」bundle + 它的分数。
// bundle = {promptVersionId?, behaviorRuleVersionId?, skillDraftId?}; iter1 为空(各面用 active)。
var best = { bundle: {}, score: null }
var keptList = []
var trajectory = []
var priorChange = null
var priorEvalReport = null
var iter = 0

for (var k = 0; k < issues.length && iter < maxIter; k++) {
  var issue = issues[k]
  iter++

  var allowedSurfaces = resolveAllowedSurfaces(issue)
  var baseBundle = best.bundle   // 各面 hill-climb base 指针 (iter1 为空)

  // 🟨 候选生成叶子(LLM, 唯一非确定性节点)。issue + allowedSurfaces + baseBundle + 反思
  // 上下文 → 跨面候选 bundle。
  var issueDesc = ctx.json({
    title: issue.title,
    severity: issue.severity,
    friction: issue.friction,
    rootCause: issue.rootCause,
    proposedFix: issue.proposedFix,
    suggestion: issue.suggestion
  })
  var candPrompt = 'targetAgentId=' + targetAgentId
    + ' reportId=' + reportId + ' issueId=' + issue.id
    + '\nallowedSurfaces=' + ctx.json(allowedSurfaces)
    + '\nbaseBundle=' + ctx.json(baseBundle)
    + '\nissue=' + issueDesc
    + (priorChange ? '\npriorChange=' + priorChange : '')
    + (priorEvalReport ? '\npriorEvalReport=' + priorEvalReport : '')
    + '\n按你的 system_prompt: 在 allowedSurfaces 白名单子集内决定改哪几面, 每面调一次'
    + ' GenerateCandidate(传该面 base 指针), 组装 candidateBundle, 只输出'
    + ' {candidateBundle, surfaces, changeDesc, prediction} 严格 JSON。'
  var cand = agent(candPrompt, { agentSlug: 'evolve-candidate-gen', schema: CAND_SCHEMA, phase: 'Evolve' })

  // whitelist 兜底 + 以 bundle 为权威重算实际改的面 (设计 §1/§3/R5)。
  var dropped = []
  var changedBundle = cand ? filterToAllowed(cand.candidateBundle || {}, allowedSurfaces, dropped) : {}
  var changedSurfaces = normalizeSurfaces(changedBundle)
  if (dropped.length > 0) {
    log('iter ' + iter + ' issue=' + issue.id + ' dropped out-of-whitelist surfaces='
      + ctx.json(dropped) + ' (allowedSurfaces=' + ctx.json(allowedSurfaces) + ')')
  }
  if (changedSurfaces.length === 0) {
    log('iter ' + iter + ' issue=' + issue.id + ' produced no in-whitelist candidate; skipping')
    continue
  }
  if (cand && Array.isArray(cand.surfaces) && cand.surfaces.length !== changedSurfaces.length) {
    log('iter ' + iter + ' issue=' + issue.id + ' surfaces declared=' + ctx.json(cand.surfaces)
      + ' but bundle-authoritative=' + ctx.json(changedSurfaces) + ' (using bundle)')
  }

  // 候选 bundle (送 A/B) = best 叠加本轮改的面; baseline = best.bundle。
  var candidateBundle = mergeBundle(baseBundle, changedBundle)

  // 🟩 多面语义 delta(确定性, JS 侧组装): 对每个 changedSurface 各调一次 GetCandidateDiff
  // → semanticDeltas 数组。每面 baseVersionId 取该面在 best.bundle 的旧指针(iter1 为 null)。
  var semanticDeltas = []
  for (var d = 0; d < changedSurfaces.length; d++) {
    var cs = changedSurfaces[d]
    var csCandidateId = bundlePointer(changedBundle, cs)
    var csBaseId = bundlePointer(baseBundle, cs)
    var diff = tool('GetCandidateDiff', {
      candidateId: csCandidateId, surface: cs, baseVersionId: csBaseId
    })
    semanticDeltas.push({
      surface: cs,
      before: diff ? diff.before : null,
      after: diff ? diff.after : null,
      diff: diff ? diff.diff : null,
      changeDesc: cand.changeDesc
    })
  }

  // 🟩 A/B(agent-surface, 整三件套 bundle)。candidate = 本轮 mergedBundle; baseline =
  // best.bundle(iter1 为空 = active), 每轮 candidate vs current-best 爬坡。
  // 注: 不传 cachedBaselineScore —— 保持 Phase 1 的全量基线复测行为(基线侧实测 best.bundle)。
  // (引擎 §7 W1 guard 要求 baseline == 最近一次 COMPLETED run 的 candidate bundle, 但本
  // keep/reject 爬坡里 best=上一个赢家, 中间夹一个未 keep 迭代就会与"最近 COMPLETED"分叉
  // → guard 抛错。基线缓存优化需先对齐 W1 语义, 留作后续, 不在 Phase 2a 多面范围内。)
  var ab = tool('TriggerAbEval', {
    surface: AB_SURFACE,
    candidateBundle: candidateBundle,
    baselineBundle: baseBundle,
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

  // 🟩 G3 对账: 把候选叶子 staked 的 prediction 跟 A/B 实际 per-scenario flip 对账 →
  // reconciliation{hits,misses,riskHits,surprises,confidence}。orchestrator 路核心特性,
  // workflow 路必须等价。
  var reconciliation = null
  if (abRunId && cand && cand.prediction) {
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

  // 🟩 落账(surface='agent'; candidateId=mergedBundle 主指针; candidateBundle=整三件套;
  // semanticDelta=数组, 经 ctx.json 序列化成 JSON 字符串透传 —— RecordIteration 的
  // putJsonSidecar 只认 Map/字符串, 原生 JS 数组会变 Java List 走 toString 损坏; 用
  // ctx.json 走它的"字符串→readTree"分支可原样存数组, 零改 Java)。
  tool('RecordIteration', {
    evolveRunId: ctx.runId(),
    iteration: iter,
    surface: AB_SURFACE,
    changeDesc: cand.changeDesc,
    candidateId: primaryPointer(candidateBundle),
    baselineScore: baselineScore,
    candidateScore: candidateScore,
    delta: delta,
    kept: keptThis,
    abRunId: abRunId,
    prediction: cand.prediction,
    reconciliation: reconciliation,
    semanticDelta: ctx.json(semanticDeltas),
    candidateBundle: candidateBundle
  })

  trajectory.push({ iteration: iter, candidateId: primaryPointer(candidateBundle), surfaces: changedSurfaces, delta: delta, kept: keptThis })
  log('iter ' + iter + ' issue=' + issue.id + ' surfaces=' + ctx.json(changedSurfaces)
    + ' candidate=' + primaryPointer(candidateBundle) + ' delta=' + delta + ' kept=' + keptThis)

  // 🟦 carry-forward: 赢家整 bundle 推进 best; 否则守住 best。
  priorChange = cand.changeDesc
  priorEvalReport = ctx.json({ delta: delta, baselineScore: baselineScore, candidateScore: candidateScore, kept: keptThis })
  if (keptThis) {
    keptList.push({ iteration: iter, candidateId: primaryPointer(candidateBundle), candidateBundle: candidateBundle, surfaces: changedSurfaces, delta: delta })
    best = { bundle: candidateBundle, score: candidateScore }
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
// ⚠️ P1 限制: EvolveController.runViaWorkflow 硬编码 autoApprove=true, 所以这条分支当前
// 走不到。humanApprove → pause → resume 在"轮询中途暂停再恢复"下的 journal-replay 未测。
// 真要开人工 gate 前必须先验 resume-mid-polling。
return humanApprove(summary)
