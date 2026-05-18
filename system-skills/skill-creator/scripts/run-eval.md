# run-eval.md — SubAgent prompt template

> 给 skill-creator skill 在自然语言入口（创建 / 优化 / 审计 skill）使用：
> 启动 2N 个 SubAgent run，每个 scenario 跑两次（with_skill / without_skill），
> 收回 transcript 后调 EvalJudgeTool 给分。
>
> 本文件被 skill-creator 的 SKILL.md step 4-5 引用，作为 SubAgent
> 派发时的 system prompt 模板（带占位符替换）。

## 何时使用

skill-creator skill 走完 step 1-3（理解需求 / 编 SKILL.md / 写 evals.json）
之后，必须 call BE 端 `SkillCreatorService.dispatchEvaluation` 跑评测。
本 prompt 模板告诉 SubAgent 自己怎么按 scenario 执行 + 报告结果。

实际的派发由 BE 处理（创建 SubAgent child session + 设置
`skill_overrides_json` + 设置 `eval_context_json`）。本文件只是 skill-creator
agent 在 BE 调用 dispatchEvaluation 之前给用户解释 / 让用户看的"我要怎么评"
脚本，不是直接执行的代码。

## SubAgent 跑 scenario 的 prompt（baseline = with_skill）

```
你是一个评测中的 sub-agent，会拿到一个用户任务 prompt。你的目标是按 prompt
要求完成任务，不要自己改 prompt、不要问回头确认、不要扩出额外步骤。

环境：
- 你被挂载了候选 skill `{{candidateSkillName}}`（即正在被评测的 skill）
- 还挂了 agent 本身原有的其它 skills

完成后简单地把最终回答输出，不需要说"评测我做完了"这类元话语。
评测系统会按你输出的内容 + 工具调用过程打分。

任务：
{{scenario.task}}
```

## SubAgent 跑 scenario 的 prompt（baseline = without_skill）

```
你是一个评测中的 sub-agent，会拿到一个用户任务 prompt。你的目标是按 prompt
要求完成任务，不要自己改 prompt、不要问回头确认、不要扩出额外步骤。

环境：
- 你**没有任何外挂 skill**（clean baseline，按 cc agentskills.io 同款约定）
- 只有 agent 本身的内建工具能力

完成后简单地把最终回答输出，不需要说"评测我做完了"这类元话语。
评测系统会按你输出的内容 + 工具调用过程打分。

任务：
{{scenario.task}}
```

## 占位符约定

| 占位符 | 来源 | 说明 |
|---|---|---|
| `{{candidateSkillName}}` | SkillEntity.name (后缀 `_eval_<8char>`) | BE 端 `SkillCreatorService.dispatchEvaluation` 渲染 transient SkillEntity 时设置 |
| `{{scenario.task}}` | EvalScenarioEntity.task | 用户在 step 4 写的 evals.json 里的 test prompt |

## 跟 BE 的关系

skill-creator skill **不**自己 spawn SubAgent。流程是：

1. skill-creator agent 走完 step 1-4（生 SKILL.md + 拿到用户给的 2-3 test case）
2. agent 把 test case 写成 ephemeral `EvalScenarioEntity`（status='ephemeral'）
3. agent 调 BE 端 `SkillCreatorService.dispatchEvaluation(parentSessionId, draftId, scenarioIds)`
4. BE 端按本文件 prompt 模板渲染 2N 个 SubAgent run（with_skill / without_skill）
5. 每个 SubAgent run 完成后由 `SkillCreatorEvalCoordinator` 监听
   `SessionLoopFinishedEvent` 自动汇总
6. 2N 全部完成 → coordinator 跑 judge + 写回 `evaluation_result_json` +
   更新 `status='evaluated_passed'` 或 `'rejected'`
7. agent 收到 BE 回信（dashboard 推送）后给用户报告评测结果

## 触发 EvalJudgeTool 的 prompt（aggregate phase）

```
你正在评测一个候选 skill 在 N 个 scenario 上的 with/without 对比。你拿到的输入：
- 候选 skill 的 SKILL.md（{{skillName}}）
- N 个 scenario，每个 scenario 含一对 transcript：with_skill / without_skill

对每对 transcript 跑 EvalJudgeTool.judgeMultiTurnConversation，拿到
compositeScore + overallScore。

聚合：
- with_skill / without_skill 各自 mean compositeScore + passRate
  （pass = compositeScore >= 0.7）
- delta = with - without 各维度

最后写一段 1-2 段的 LLM summary 说明：
- 这个 skill 在哪些 scenario 上明显帮到 agent / 哪些没帮
- 总体推荐：promote / reject / iterate
- 如果 reject，说明可能的 SKILL.md 改进方向（让用户下一轮看）
```

BE 端 coordinator 已经实现这个聚合（Phase 1.1 的 `SkillCreatorEvalCoordinator
.aggregate`），所以本 prompt 在 Phase 1.6 dogfood 真接 LLM judge 时才会实际用。
当前（Phase 1.1 占位实现）用 runtime_status 当 compositeScore proxy。
