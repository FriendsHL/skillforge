export const meta = {
  name: 'evolve-loop',
  description: 'AUTOEVOLVE 确定性进化循环 DSL 版: (report) → 写死排序 issue → 错题本靶向(ListActiveHarvestedScenarios) → 逐 issue { 候选叶子(跨面 bundle) → 多面 GetCandidateDiff → TriggerAbEval(agent, evalScenarioIds + win-streak 基线缓存) → 确定性轮询 GetAbResult → 服务端阈值 keep(最小测量数守卫 + vs-original 锚点) → RecordIteration } → carry-forward bundle → adopt(人定夺/autoApprove)。编排全在 JS(无 maxLoops/无漂移)，LLM 只做候选生成叶子。',
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

// ── 确定性 keep 判断 (复用 GetAbResult.wouldPromote 作种子 + 两道闸) ──
// wouldPromote 是服务端 agent 面双标准 advisory (F7 修正旧 "15pp" 注释——那是 prompt/
// skill 面的旧阈值, agent 面从来不是): 有 target 子集时 targetDeltaPp > agent-target-
// min-delta-pp(默认0) AND regressionDeltaPp >= agent-regression-floor-pp(默认-3);
// 无 target 子集(regression-only, 含 F2 "target 在场但 0 measured" null 哨兵)时
// general 严格改善才 true。所有阈值由服务端配置 (skillforge.evolve.thresholds) 并经
// res.thresholds 回显, JS 不写死第二份。
// 另加两道闸:
//   ① F3 最小测量数守卫: overall measuredN < minMeasuredN → inconclusive 不 keep
//      (防 n≈7 噪声 keep)。只看 overall, 不卡 target 子集 (target 合法可只有 1 个
//      确定性 oracle 场景)。measuredN 缺失 (legacy 行) 时跳过守卫 —— F2 的 null 哨兵
//      保证 "0 measured" 时 measuredN=0 是数字, 不会从这里漏掉。
//   ② F6 vs-original 锚点: candidateGeneralRate >= originalGeneral - anchorErosion
//      FloorPp。originalGeneral = iter1 的 baselineGeneralRate (记死不更新), 防多轮
//      vs-best 爬坡把 general 一点点磨掉。originalGeneral / candidateGeneralRate
//      为 null 时跳过锚点 (不 block)。
// 返回 {keep, reason} 便于轨迹日志说明为什么没 keep。
function decideKeep(res, originalGeneral) {
  if (!res || res.wouldPromote !== true) {
    return { keep: false, reason: 'wouldPromote=false' }
  }
  var th = res.thresholds || {}
  if (typeof th.minMeasuredN === 'number' && typeof res.measuredN === 'number'
      && res.measuredN < th.minMeasuredN) {
    return { keep: false, reason: 'inconclusive: measuredN=' + res.measuredN
      + ' < minMeasuredN=' + th.minMeasuredN }
  }
  if (typeof th.anchorErosionFloorPp === 'number'
      && typeof originalGeneral === 'number'
      && typeof res.candidateGeneralRate === 'number'
      && res.candidateGeneralRate < originalGeneral - th.anchorErosionFloorPp) {
    return { keep: false, reason: 'anchor erosion: candidateGeneralRate='
      + res.candidateGeneralRate + ' < originalGeneral(' + originalGeneral
      + ') - anchorErosionFloorPp(' + th.anchorErosionFloorPp + ')' }
  }
  return { keep: true, reason: 'kept' }
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

// 🟦 F1 靶向恢复 (BC-M2b, 迁移遗漏修复): Report 后一次性取 active 错题本场景 ids。
// 非空时每轮 TriggerAbEval 带 evalScenarioIds (镜像 V144 orchestrator 语义: 这些作
// target 子集, 其余 dataset 照常作 general/benchmark, 同一 run 双子集都测);
// 空 → 不传, 保持 role-split 现行为。一次性取(不每轮取): run 内 target 集稳定,
// 与 dataset version 跨轮稳定的 §8 子点① 不变量一致。
var harvested = tool('ListActiveHarvestedScenarios', { agentId: agentIdStr })
var targetScenarioIds = (harvested && harvested.scenarioIds && harvested.scenarioIds.length > 0)
  ? harvested.scenarioIds : null
log('evolve-loop harvested-target-scenarios=' + (targetScenarioIds ? targetScenarioIds.length : 0))

// carry-forward 状态: best 是「各面当前最优组合」bundle + 它的分数 + 测出它的 abRunId
// (F4: win-streak 时作 priorWinnerAbRunId 传给下一轮)。
// bundle = {promptVersionId?, behaviorRuleVersionId?, skillDraftId?}; iter1 为空(各面用 active)。
var best = { bundle: {}, score: null, abRunId: null }
var keptList = []
var trajectory = []
var priorChange = null
var priorEvalReport = null
var prevKept = false        // F4: 上一轮(真测过的)是否被 keep —— win-streak 标记
var originalGeneral = null  // F6: iter1 的 baselineGeneralRate, 记死不更新
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
  // F1: targetScenarioIds 非空时带 evalScenarioIds → 错题本场景作显式 target 子集。
  // F4 基线缓存 (win-streak 限定, 替代旧 js:249-254 的"整体推迟"): 仅当上一轮被 keep
  // 时传 cachedBaselineScore(best 的实测分) + priorWinnerAbRunId(测出 best 的那次
  // abRunId) → 引擎按显式 run id 解析 prior winner (W1 guard 重设计: 不再依赖
  // "最近一次 COMPLETED", 被拒 run 夹在中间也不会让 guard 抛错), candidate-only 跑,
  // 省一半 eval 且基线无重测噪声。上一轮被拒 → 不传, 下一轮照常真测两臂。
  var abInput = {
    surface: AB_SURFACE,
    candidateBundle: candidateBundle,
    baselineBundle: baseBundle,
    targetAgentId: agentIdStr,
    evolveRunId: ctx.runId()
  }
  if (targetScenarioIds) {
    abInput.evalScenarioIds = targetScenarioIds
  }
  if (prevKept && best.score != null && best.abRunId != null) {
    abInput.cachedBaselineScore = best.score
    abInput.priorWinnerAbRunId = best.abRunId
  }
  var ab = tool('TriggerAbEval', abInput)
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

  // 🟦 F6: iter1 记 vs-original general 锚点 (第一轮记死, 后续不更新)。
  if (originalGeneral == null && res && typeof res.baselineGeneralRate === 'number') {
    originalGeneral = res.baselineGeneralRate
  }

  // 🟦 确定性 keep 判断(服务端阈值回显 + wouldPromote 种子 + F3/F6 两道闸)。
  var decision = decideKeep(res, originalGeneral)
  var keptThis = decision.keep
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

  trajectory.push({ iteration: iter, candidateId: primaryPointer(candidateBundle), surfaces: changedSurfaces, delta: delta, kept: keptThis, keepReason: decision.reason })
  log('iter ' + iter + ' issue=' + issue.id + ' surfaces=' + ctx.json(changedSurfaces)
    + ' candidate=' + primaryPointer(candidateBundle) + ' delta=' + delta + ' kept=' + keptThis
    + (keptThis ? '' : ' (' + decision.reason + ')'))

  // 🟦 carry-forward: 赢家整 bundle 推进 best (带 abRunId 供 F4 下一轮缓存); 否则守住 best。
  priorChange = cand.changeDesc
  priorEvalReport = ctx.json({ delta: delta, baselineScore: baselineScore, candidateScore: candidateScore, kept: keptThis, keepReason: decision.reason })
  prevKept = keptThis
  if (keptThis) {
    keptList.push({ iteration: iter, candidateId: primaryPointer(candidateBundle), candidateBundle: candidateBundle, surfaces: changedSurfaces, delta: delta })
    best = { bundle: candidateBundle, score: candidateScore, abRunId: abRunId }
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
