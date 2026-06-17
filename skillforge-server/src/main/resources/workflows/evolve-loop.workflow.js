export const meta = {
  name: 'evolve-loop',
  description: 'AUTOEVOLVE 确定性爬坡循环 DSL 版 (EVOLVE-LOOP-HILLCLIMB 阶段 A): (report) → 全部 issue 当静态线索库 → 错题本靶向(ListActiveHarvestedScenarios) → for-iter { 候选叶子(看全部线索+currentBest+history 自主决策改哪面，跨面 bundle) → 多面 GetCandidateDiff → TriggerAbEval(agent, vs best, evalScenarioIds + win-streak 基线缓存) → 确定性轮询 GetAbResult → 配对 net-wins 主判据 keep(F3 最小测量数守卫 + F6 vs-original 锚点, weightedScore 降 advisory) → RecordIteration → carry-forward best } → 停止(达标 OR 收敛 OR 跑满 maxIter) → 返回全程最优 best + 轨迹 → adopt(人定夺/autoApprove)。编排全在 JS(无 maxLoops/无漂移)，LLM 只做候选生成叶子。',
  phases: [
    { title: 'Report', detail: '取/跑 opt-report，全部 issue 进静态线索库' },
    { title: 'Evolve', detail: 'for-iter 爬坡: 候选(看全局自主决策) + A/B(vs best) + 配对 net-wins keep(weightedScore advisory) + 落账' },
    { title: 'Adopt', detail: 'autoApprove 直通 / 否则人工 gate' }
  ]
}

// 候选叶子的严格输出 schema (跨面 bundle)。candidateBundle 是真 id 三件套(各面 candidateId
// 来自 GenerateCandidate service 持久化后的真实返回，叶子只转发，伪造不了)；surfaces 是叶子
// 声明实际改的面(JS 侧以 candidateBundle 非空指针为权威重算，见 normalizeSurfaces)；prediction
// 可选(无 scenario id 时留空数组)。
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

// 三个可生成的优化面 (agent 是 A/B 路由元面，不是可生成面)。canonical 顺序固定，用于 bundle
// 指针归一、whitelist 过滤、semanticDelta 顺序。EVOLVE-LOOP-HILLCLIMB: allowedSurfaces 恒为
// 全三面(放开白名单，agent 自选本轮改哪面)，filterToAllowed 仍以 SURFACES 作 allowed 兜底防
// agent 吐非法 key。
var SURFACES = ['prompt', 'behavior_rule', 'skill']

// A/B 评测走 agent-surface 引擎 (整三件套 bundle)。**为什么不是各面单独 A/B**：
// 只有 agent-surface A/B(t_agent_evolve_ab_run) 存 per-scenario flip，ReconcilePrediction
// 才能算 G3 对账(hits/misses/confidence)，且一个 bundle 一次触发(不按面计 budget)。
// 这与 orchestrator 路一致(它也用 agent-surface A/B 才能产 reconciliation)。
var AB_SURFACE = 'agent'

function isNum(x) {
  return typeof x === 'number'
}

function nonBlank(s) {
  return s != null && ('' + s).length > 0
}

function truncate(s, n) {
  if (s == null) return s
  var str = '' + s
  return str.length > n ? str.slice(0, n) : str
}

// ── EVOLVE-JUDGE-GROUNDING Phase 1 确定性 keep 判断 (配对 net-wins 主判据 + 两道闸) ──
// 主判据(Q1): 服务端预算的 comparativeVerdict.significant —— 候选 vs best 同场景配对的
// netWins(=improvedTotal-regressedTotal) >= minNetWins [+ 可选符号检验]。weightedScore 降为
// advisory(仍写 RecordIteration/轨迹, 但不再是 keep 判据)。
// 两道闸(沿用, 前置在配对判据之前 —— INV-1):
//   ① F3 最小测量数守卫: overall measuredN < minMeasuredN → inconclusive 不 keep
//      (防 n≈7 噪声: 样本太小时配对净胜无意义)。measuredN/minMeasuredN 缺失时跳过守卫。
//   ② F6 vs-original 锚点: candidateGeneralRate >= originalGeneral - anchorErosionFloorPp。
//      originalGeneral = iter1 的 baselineGeneralRate (记死不更新)，防多轮爬坡把 general
//      磨掉。originalGeneral / candidateGeneralRate 为 null 时跳过锚点 (不 block)。
// first-candidate(INV-2): comparativeVerdict 缺失(无 per-scenario 配对数据, 如 iter1 无对照)
//   → 退回 weightedScore 有信号即立为首个 best (keep)，保留原"首个有信号候选立 best"语义。
// 返回 {keep, reason}，reason 打印 improved/regressed/net + 判据 (INV-3 轨迹可解释)。
function decideKeep(res, best, originalGeneral) {
  if (!res || !isNum(res.weightedScore)) {
    return { keep: false, reason: 'no weightedScore signal' }
  }
  var th = res.thresholds || {}
  // ① F3 最小测量数守卫 (前置, INV-1)。
  if (isNum(th.minMeasuredN) && isNum(res.measuredN) && res.measuredN < th.minMeasuredN) {
    return { keep: false, reason: 'inconclusive: measuredN=' + res.measuredN
      + ' < minMeasuredN=' + th.minMeasuredN }
  }
  // ② F6 vs-original 锚点 (前置, INV-1)。
  if (isNum(th.anchorErosionFloorPp) && isNum(originalGeneral) && isNum(res.candidateGeneralRate)
      && res.candidateGeneralRate < originalGeneral - th.anchorErosionFloorPp) {
    return { keep: false, reason: 'anchor erosion: candidateGeneralRate='
      + res.candidateGeneralRate + ' < originalGeneral(' + originalGeneral
      + ') - anchorErosionFloorPp(' + th.anchorErosionFloorPp + ')' }
  }
  // 配对主判据 (Q1): comparativeVerdict 由服务端从 per-scenario flip totals 预算。
  // 注意: 这个分支只在 cv 真缺失 (null / netWins 非数) 时进 —— netWins===0 (平手) 是
  // 有效配对结论, 走下方 significant 判定 (按 minNetWins 拒), 不当"无 cv"处理。
  var cv = res.comparativeVerdict
  if (!cv || !isNum(cv.netWins)) {
    // first-candidate / 无配对对照 (INV-2): 退回首个有信号候选立为 best。
    if (!isNum(best.weightedScore)) {
      return { keep: true, reason: 'first measured candidate (no comparative baseline)' }
    }
    return { keep: false, reason: 'no comparativeVerdict (paired data absent); '
      + 'weightedScore=' + res.weightedScore + ' advisory-only, not kept' }
  }
  var minNet = isNum(th.minNetWins) ? th.minNetWins : 2
  var evidence = 'improved=' + cv.improvedTotal + ' regressed=' + cv.regressedTotal
    + ' net=' + (cv.netWins >= 0 ? '+' : '') + cv.netWins
    + ' (minNetWins=' + minNet + (th.pairwiseSignTest ? ', signTest@' + th.pairwiseAlpha : '') + ')'
    + ' weightedScore=' + res.weightedScore + ' (advisory)'
  if (cv.significant === true) {
    return { keep: true, reason: 'comparative keep: ' + evidence }
  }
  return { keep: false, reason: 'comparative reject: ' + evidence }
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

// whitelist 兜底: 丢掉 allowed 之外的任何面指针 (叶子越界保护，确定性，不信 LLM)。
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

// ── 写死的 issue 选择 + 排序 (设计 §6 不用 agent 叶子) ──
// 只取 convertible 的 issue；按 severity×recurrence×confidence 降序。爬坡形态下这是给候选叶子
// 的"全部线索库"(叶子综观全部自主决策本轮改哪面)，不再是逐条 pop 的循环变量。
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

// 把排序后的 issue 压成给候选叶子的精简线索(截断 rootCause/proposedFix/suggestion 防 prompt 膨胀)。
function briefIssues(issues) {
  return issues.map(function (it) {
    return {
      id: it.id,
      title: it.title,
      severity: it.severity,
      recurrence: it.recurrence,
      confidence: it.confidence,
      rootCause: truncate(it.rootCause, 500),
      proposedFix: truncate(it.proposedFix, 500),
      suggestion: truncate(it.suggestion, 500),
      fixSurface: it.fixSurface
    }
  })
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
  return { status: 'empty', reportId: reportId, evaluated: 0, kept: 0, trajectory: [], stopReason: 'empty' }
}

// 全部线索 once (issue 静态，每轮喂候选叶子作"全局线索库")。
var allIssuesBrief = briefIssues(issues)

// ── Evolve (确定性爬坡循环, 无 maxLoops/无漂移) ──
phase('Evolve')

// 🟦 F1 靶向恢复: Report 后一次性取 active 错题本场景 ids。非空时每轮 TriggerAbEval 带
// evalScenarioIds (作 target/harvest 子集, 其余 dataset 作 general 子集, 同 run 双子集都测);
// 空 → 不传, 保持 role-split 现行为。一次性取(不每轮取): run 内 target 集稳定。
var harvested = tool('ListActiveHarvestedScenarios', { agentId: agentIdStr })
var targetScenarioIds = (harvested && harvested.scenarioIds && harvested.scenarioIds.length > 0)
  ? harvested.scenarioIds : null
log('evolve-loop harvested-target-scenarios=' + (targetScenarioIds ? targetScenarioIds.length : 0))

// carry-forward 状态: best 是「各面当前最优组合」bundle + 它的分数 (score=composite, weightedScore=
// 加权主判据, generalRate/harvestRate=两子集分) + 测出它的 abRunId (F4: win-streak 时作
// priorWinnerAbRunId 传给下一轮)。bundle = {promptVersionId?, behaviorRuleVersionId?,
// skillDraftId?}; iter1 为空(各面用 active)。
var best = { bundle: {}, score: null, weightedScore: null, generalRate: null, harvestRate: null, abRunId: null }
var keptList = []
var trajectory = []
var history = []           // 喂候选叶子的历轮记录(改了啥/整体&per-case 涨跌/留弃)
var prevKept = false       // F4: 上一轮(真测过的)是否被 keep —— win-streak 标记
var originalGeneral = null // F6: iter1 的 baselineGeneralRate, 记死不更新
var noImprove = 0          // 收敛停: 连续无新 best 轮数
var streakLimit = 3        // 收敛停阈值(默认, 每轮 A/B 后从 res.thresholds 刷新)
var abRounds = 0           // 真正进入 A/B 的轮数(summary.evaluated)
var stopReason = 'maxIter'

for (var iter = 1; iter <= maxIter; iter++) {
  var baseBundle = best.bundle   // 各面 hill-climb base 指针 (iter1 为空)

  // 🟨 候选生成叶子(LLM, 唯一非确定性节点)。allIssues + currentBest + history → 自主决策本轮
  // 改哪面、跨面候选 bundle。allowedSurfaces 恒为全三面(放开, agent 自选)。
  var candPrompt = 'targetAgentId=' + targetAgentId + ' reportId=' + reportId
    + '\nallowedSurfaces=' + ctx.json(SURFACES)
    + '\ncurrentBest=' + ctx.json({
        weightedScore: best.weightedScore,
        generalPassRate: best.generalRate,
        harvestPassRate: best.harvestRate,
        bundle: best.bundle
      })
    + '\nallIssues=' + ctx.json(allIssuesBrief)
    + '\nhistory=' + ctx.json(history)
    + '\n按你的 system_prompt: 综观 allIssues + currentBest + history, 自主判断本轮整体最该调'
    + '哪个/哪几个面、怎么改, 把 weightedScore 往上推。对每个选中面调一次 GenerateCandidate'
    + '(传 currentBest.bundle 里该面的基线指针), 组装 candidateBundle, 只输出'
    + ' {candidateBundle, surfaces, changeDesc, prediction} 严格 JSON。'
  var cand = agent(candPrompt, { agentSlug: 'evolve-candidate-gen', schema: CAND_SCHEMA, phase: 'Evolve' })

  // whitelist 兜底 + 以 bundle 为权威重算实际改的面 (设计 §1/§3/R5)。allowed = SURFACES (全集)。
  var dropped = []
  var changedBundle = cand ? filterToAllowed(cand.candidateBundle || {}, SURFACES, dropped) : {}
  var changedSurfaces = normalizeSurfaces(changedBundle)
  if (dropped.length > 0) {
    log('iter ' + iter + ' dropped out-of-whitelist surfaces=' + ctx.json(dropped))
  }
  if (changedSurfaces.length === 0) {
    log('iter ' + iter + ' produced no in-whitelist candidate; counting as no-improve')
    noImprove++
    trajectory.push({ iteration: iter, kept: false, keepReason: 'no candidate' })
    if (noImprove >= streakLimit) { stopReason = 'converged'; break }
    continue
  }
  if (cand && Array.isArray(cand.surfaces) && cand.surfaces.length !== changedSurfaces.length) {
    log('iter ' + iter + ' surfaces declared=' + ctx.json(cand.surfaces)
      + ' but bundle-authoritative=' + ctx.json(changedSurfaces) + ' (using bundle)')
  }

  // 候选 bundle (送 A/B) = best 叠加本轮改的面; baseline = best.bundle。
  var candidateBundle = mergeBundle(baseBundle, changedBundle)

  // 🟩 多面语义 delta(确定性): 对每个 changedSurface 各调一次 GetCandidateDiff → 数组。
  // 每面 baseVersionId 取该面在 best.bundle 的旧指针(iter1 为 null)。
  var semanticDeltas = []
  for (var d = 0; d < changedSurfaces.length; d++) {
    var cs = changedSurfaces[d]
    var csCandidateId = bundlePointer(changedBundle, cs)
    var csBaseId = bundlePointer(baseBundle, cs)
    var diff = tool('GetCandidateDiff', { candidateId: csCandidateId, surface: cs, baseVersionId: csBaseId })
    semanticDeltas.push({
      surface: cs,
      before: diff ? diff.before : null,
      after: diff ? diff.after : null,
      diff: diff ? diff.diff : null,
      changeDesc: cand.changeDesc
    })
  }

  // 🟩 A/B(agent-surface, 整三件套 bundle)。candidate = 本轮 mergedBundle; baseline = best.bundle
  // (iter1 为空 = active), 每轮 candidate vs current-best 爬坡。F1: targetScenarioIds 非空时带
  // evalScenarioIds。F4 基线缓存(win-streak 限定): 仅当上一轮被 keep 时传 cachedBaselineScore
  // (best 的实测分) + priorWinnerAbRunId(测出 best 的那次 abRunId) → 引擎按显式 run id 解析
  // prior winner, candidate-only 跑, 省一半 eval。上一轮被拒 → 不传, 下一轮照常真测两臂。
  abRounds++
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

  // 🟩 G3 对账: 候选叶子 staked 的 prediction 跟 A/B 实际 per-scenario flip 对账。
  var reconciliation = null
  if (abRunId && cand && cand.prediction) {
    var recon = tool('ReconcilePrediction', {
      prediction: cand.prediction, abRunId: abRunId, targetAgentId: agentIdStr
    })
    if (recon && recon.status !== 'rejected') {
      reconciliation = recon
    }
  }

  // 服务端回显的停止阈值 (收敛阈值 / 达标阈值)。每轮刷新 streakLimit (no-candidate 分支沿用)。
  if (res && res.thresholds && isNum(res.thresholds.noImproveStreakLimit)) {
    streakLimit = res.thresholds.noImproveStreakLimit
  }
  var targetWS = (res && res.thresholds && isNum(res.thresholds.targetWeightedScore))
    ? res.thresholds.targetWeightedScore : null   // null = 不靠达标停

  // 🟦 F6: iter1 记 vs-original general 锚点 (第一轮记死, 后续不更新)。
  if (originalGeneral == null && res && isNum(res.baselineGeneralRate)) {
    originalGeneral = res.baselineGeneralRate
  }
  // iter1: 用 baseline 侧给 best 一个起点 (active 配置的加权分/分数), 让 hill-climb 有底。
  if (best.weightedScore == null && res && isNum(res.baselineWeightedScore)) {
    best.weightedScore = res.baselineWeightedScore
    best.generalRate = isNum(res.baselineGeneralRate) ? res.baselineGeneralRate : null
    best.harvestRate = isNum(res.baselineTargetRate) ? res.baselineTargetRate : null
    best.score = isNum(res.baselineScore) ? res.baselineScore : null
  }

  // 🟦 确定性 keep 判断 (配对 net-wins 主判据 + F3/F6 两道闸, INV-1 前置)。
  var decision = decideKeep(res, best, originalGeneral)
  var keptThis = decision.keep
  var delta = res ? res.delta : null
  var baselineScore = res ? res.baselineScore : null
  var candidateScore = res ? res.candidateScore : null
  var candWeighted = (res && isNum(res.weightedScore)) ? res.weightedScore : null

  // 🟩 落账(surface='agent'; weightedScore advisory-only 落账; semanticDelta=数组经 ctx.json 透传)。
  tool('RecordIteration', {
    evolveRunId: ctx.runId(),
    iteration: iter,
    surface: AB_SURFACE,
    changeDesc: cand.changeDesc,
    candidateId: primaryPointer(candidateBundle),
    baselineScore: baselineScore,
    candidateScore: candidateScore,
    delta: delta,
    weightedScore: candWeighted,
    baselineWeightedScore: (res && isNum(res.baselineWeightedScore)) ? res.baselineWeightedScore : null,
    kept: keptThis,
    abRunId: abRunId,
    prediction: cand.prediction,
    reconciliation: reconciliation,
    semanticDelta: ctx.json(semanticDeltas),
    candidateBundle: candidateBundle
  })

  // per-case 回归名 (history grounding 给候选叶子当反例)。
  var perCaseRegressed = (res && res.perScenarioFlips && res.perScenarioFlips.regressed)
    ? res.perScenarioFlips.regressed.map(function (f) { return f.scenarioName || f.scenarioId })
    : []
  history.push({
    iter: iter,
    changeDesc: cand.changeDesc,
    weightedScore: candWeighted,
    delta: delta,
    perCaseRegressed: perCaseRegressed,
    kept: keptThis,
    keepReason: decision.reason
  })
  trajectory.push({
    iteration: iter,
    candidateId: primaryPointer(candidateBundle),
    surfaces: changedSurfaces,
    delta: delta,
    weightedScore: candWeighted,
    kept: keptThis,
    keepReason: decision.reason
  })
  log('iter ' + iter + ' surfaces=' + ctx.json(changedSurfaces) + ' candidate='
    + primaryPointer(candidateBundle) + ' weightedScore=' + candWeighted + ' delta=' + delta
    + ' kept=' + keptThis + (keptThis ? '' : ' (' + decision.reason + ')'))

  // 🟦 carry-forward: 赢家整 bundle 推进 best (带 abRunId 供 F4 下一轮缓存); 否则守住 best。
  prevKept = keptThis
  if (keptThis) {
    keptList.push({
      iteration: iter, candidateId: primaryPointer(candidateBundle),
      candidateBundle: candidateBundle, surfaces: changedSurfaces,
      delta: delta, weightedScore: candWeighted
    })
    best = {
      bundle: candidateBundle,
      score: candidateScore,
      weightedScore: candWeighted,
      generalRate: (res && isNum(res.candidateGeneralRate)) ? res.candidateGeneralRate : null,
      harvestRate: (res && isNum(res.candidateTargetRate)) ? res.candidateTargetRate : null,
      abRunId: abRunId
    }
    noImprove = 0
  } else {
    noImprove++
  }

  // 🟦 停止条件 (先达标后收敛; 跑满 maxIter 由 for 边界兜底)。
  if (targetWS != null && isNum(best.weightedScore) && best.weightedScore >= targetWS) {
    stopReason = 'target'
    break
  }
  if (noImprove >= streakLimit) {
    stopReason = 'converged'
    break
  }
}

// ── Adopt (Iron-Law 人类闸门: 实际 adopt 仍走人类 POST /runs/{id}/adopt) ──
phase('Adopt')
var summary = {
  status: 'completed',
  reportId: reportId,
  evaluated: abRounds,
  kept: keptList.length,
  keptCandidates: keptList,
  best: best,
  trajectory: trajectory,
  stopReason: stopReason
}
if (autoApprove) {
  return summary
}
// 非 autoApprove(人触发): 末尾人工 gate 看 kept 候选 + 轨迹。
// ⚠️ P1 限制: EvolveController.runViaWorkflow 硬编码 autoApprove=true, 所以这条分支当前
// 走不到。humanApprove → pause → resume 在"轮询中途暂停再恢复"下的 journal-replay 未测。
// 真要开人工 gate 前必须先验 resume-mid-polling。
return humanApprove(summary)
