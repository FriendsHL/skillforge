# SKILL-CREATOR-WITH-EVAL MRD

---
id: SKILL-CREATOR-WITH-EVAL
status: design-draft
created: 2026-05-18
---

## 痛点

V1 创建 `system-skills/skill-creator/SKILL.md` 时按 cc agentskills.io 同款 7 步 workflow 写了 evaluation 概念 (步骤 4 创建 evals + 步骤 5 SubAgent 跑 with-skill vs baseline), 但 **真实施时只 render SKILL.md, 没跑 evaluation**:

- `system-skills/skill-creator/scripts/` 目录不存在
- `system-skills/skill-creator/evals/` 不存在
- `SkillCreatorService.java` 仅 `render(draft, targetDir)` 写 SKILL.md, 不调 LLM eval, 不 SubAgent fork, 不算 benchmark
- 4 个 skill 创建入口 (用户上传 / marketplace 下载 / 自然语言描述 / extract from sessions) **任意入口 land 一个 skill 后立刻挂给 agent, 没 evaluation gate**

结果:
- **用户没 evidence 知道 skill 真值不值** (新 skill 加进来 agent 变更好还是更差?)
- **低质量 skill 静默上线** (extract 误抽 / 用户上传烂 SKILL.md / marketplace 杂质)
- **飞轮原料污染**: 烂 skill 进 production 后跑出更多 failure session, 反过来又被 extract 当 candidate, 死循环

## 用户场景

### 场景 A: 用户自然语言描述创 skill

```
user: 帮我创建一个 csv-analyzer skill, 能分析 CSV 找 top N 数据 + 画图
agent (attach skill-creator):
  step 1: 问你要不要给 2-3 个使用示例 (顺手收 test cases)
  step 2-3: 写 SKILL.md
  step 4-5: SubAgent fork × 2 跑 test cases:
    - with_skill: agent + 新 csv-analyzer skill → 跑 test prompt
    - without_skill: agent skillIds=[] → 跑 test prompt
  step 6: 调 EvalJudgeTool 判 2 路 transcript → benchmark.json
  step 7: report
    ✓ with_skill pass_rate 100% / without_skill pass_rate 33% / delta +67pp
    ✓ token cost +500 / latency +2.3s
    ✓ LLM 总结: "这个 skill 在 CSV 分析任务上显著提升, 推荐 promote"

user: 看完报告决定 promote / reject / iterate
```

vs 当前 (没 eval): 用户写完 SKILL.md 直接 attach, 不知道 skill 真值不值.

### 场景 B: Extract from sessions cron

```
cron 周一凌晨 trigger extractFromRecentSessions(agentId=3 Code Agent):
  - 拿 Code Agent 最近 7 天 production session N 条 (filter outcome=failure)
  - LLM 抽 common failure pattern → 生 SkillDraft
  - 自动调 evaluateSkillDraft:
    - 评测集 = 这批 N session (turn 1 prompt + 原 response baseline)
    - SubAgent × 2 跑 with/without
    - benchmark + LLM 总结
  - delta ≥ 5pp → status='evaluated_passed' → 进 attribution path (operator 审 approve)
  - delta < 5pp → status='rejected' + LLM 原因 → operator dashboard 看 reject list iterate
```

vs 当前: extract 跑完 SkillDraft 直接进 attribution path, 不评 quality 就让 operator 审 — operator 不知道 skill 真值多少.

### 场景 C: 用户上传 zip skill

```
user 上传 my-csv-analyzer.zip 含:
- SKILL.md
- scripts/
- evals/evals.json (含 2 test prompt)

SkillUpload 路径:
- 解 zip
- 调 SkillCreatorService.evaluateSkillDraft(draft, eval scenarios from zip)
- SubAgent × 2 跑 → benchmark
- delta < 阈值 → status='rejected' 不 attach 给 agent + 给用户报告 reject reason
- delta ≥ 阈值 → 正常 attach
```

vs 当前: 上传立刻 attach, 没把关.

### 场景 D: Marketplace 下载

类似场景 C, 但 zip 来源是 marketplace (V1 SKILL-IMPORT-BATCH 路径). 加 evaluation gate 防 marketplace 杂质.

## 跟 cc agentskills.io 对照

| 维度 | cc agentskills.io | 本需求实施 |
|---|---|---|
| **核心思路** | eval-driven iteration loop | ✅ 同款 |
| **baseline** | with_skill vs **without_skill** (NO_SKILL) | ✅ 同款 |
| **scenario 来源** | author 手写 evals/evals.json | ✅ 入口 1+2 同款; 入口 3 agent 问用户; 入口 4 复用原 session |
| **SubAgent path** | spawn run with clean context | ✅ SkillForge SubAgent infra (CLAUDE.md known) |
| **grade** | per-assertion PASS/FAIL + evidence | ⚠️ **本需求不实施**, 复用 V5 EvalJudgeTool composite_score (per-assertion 拆分留 EVAL-ASSERTIONS-EVIDENCE backlog) |
| **benchmark.json** | with/without pass_rate + delta + time/tokens | ✅ 同款 + LLM 总结 |
| **stop conditions** | 满意 / feedback 空 / 不再 improve | ⚠️ 本需求 1 round eval, iterate 留 operator manual |
| **skill-creator skill (meta dogfood)** | cc skill-creator skill 自己 automate workflow | ✅ V1 已建 skill-creator skill, 本需求加 scripts/ + evals/ 完成 meta |
| **blind comparison** | LLM judge 不知 with/without | ⚠️ 不实施 (接受 bias risk) |

## 不在 MRD 范围内

- 不实施 per-assertion evidence-based grading (独立 backlog `EVAL-ASSERTIONS-EVIDENCE`)
- 不实施 NO_SKILL baseline for 飞轮 attribution path (V6 EMPTY_SKILL path 保留, 本需求只 skill-creator 用 NO_SKILL)
- 不实施 30 天 NO_SKILL "skill 必要性 check" cron (backlog 候选)
- 不实施 blind comparison judge
- 不动 SkillAbEvalService / V2-V4 A/B 框架内部
- 不动 Iron Law 核心 7+1 BE + 3 FE

## 用户 quote / 决策来源

- 2026-05-18 与 user dogfood 聊 V8 后切到 CC-SKILL-EVAL-METHODOLOGY 调研, 拍板"skill-creator 是 SkillForge skill (不是 system agent), 本期补 evaluation 实施"
- 8 design 决策见 prd.md "## 决策记录" section
