# Think Before Coding

> 来源：[Karpathy LLM coding pitfalls](https://x.com/karpathy/status/2015883857489522876) → [forrestchang/andrej-karpathy-skills](https://github.com/forrestchang/andrej-karpathy-skills) 4 条原则中，SkillForge 的 system prompt 与现有 rules 未显式覆盖的部分。
>
> 其余 3 条已被覆盖，本文件不重复：
> - **Simplicity First** → system prompt（不加多余功能 / 不为不可能场景加 validation / 不写 WHAT 注释）
> - **Surgical Changes**（主体）→ system prompt（"bug 修复不需要附带 cleanup"）
> - **Goal-Driven Execution** → [`common/testing.md`](common/testing.md) TDD + [`pipeline.md`](pipeline.md) Full Pipeline 对抗循环
>
> 本文件解决的是 Karpathy 指出但 SkillForge 没明确写的两类毛病：**沉默地选一种解读 / 顺手清理无关 dead code**。

## 非 trivial 任务开工前 4 条

1. **暴露假设** —— 对意图、边界、技术选型、字段语义不确定时**先问**，不要默认一个跑下去；做完再说"我假设你想要 X" 是事后 rework
2. **多解读一并端出** —— 指令存在 ≥2 种合理解读，**列出来让用户选**，不要静默挑一个就开干
3. **更简方案 push back** —— 用户指定的方案 vs 一个明显更简单的等价方案，先指出，给用户决策；不是默默换掉，也不是默默照做
4. **困惑就停** —— 不要"边做边猜"。卡在哪里、为什么卡，**说出来**，问

> 与 system prompt "exploratory questions 先给推荐再实施" 的区别：那条只覆盖"how should we…"这种探索性提问；本条覆盖**任何指令**只要存在歧义都要先暴露。

## 重大设计决策的 HARD-GATE（来自 superpowers/brainstorming）

> **适用范围**：brief >800 字 / 红灯触发 Full 档 / 涉及核心文件清单的任务。Mid 档普通 bug 修复 / UI 调整不适用。

**不准在没有"设计文档 + 用户批准"前 implement**，即使任务看起来很简单。

最小设计文档（写在需求包 `prd.md` / `tech-design.md` 或 Plan 文件里）：
- 目标 / 验收点
- 2-3 种实现路径，带 tradeoff
- 推荐路径 + 理由
- 最可能踩坑的边界 / 隐性 invariant

**反模式："这个任务太简单不需要 design"** —— "简单"项目恰恰是 unexamined assumptions 浪费工作最多的地方。短设计也是设计，但必须有用户批准。

## Spec self-review checklist（设计文档写完自检）

设计文档（`prd.md` / `tech-design.md` / Plan 文件）写完之后，走一遍：

1. **Placeholder scan** —— 还有 "TBD / TODO / 待定 / ?" 吗？补完或显式标"暂留"
2. **内部一致性** —— 各章节是否互相矛盾？架构和 feature description 对得上吗？
3. **Scope check** —— 这个范围一份 plan 能搞完吗？大了就拆 sub-projects（每个 sub-project 单独 spec → plan → implement）
4. **Ambiguity check** —— 任何需求是不是有两种合理解读？挑一个 + 显式写明

发现问题 **inline 改**，不需要再过一轮 review，改完给用户。

## 任务进行中 1 条

5. **不顺手清理无关 dead code** —— 任务过程中发现 unused import / 过期注释 / dead branch / 看着别扭的命名，**mention 给用户，不要顺手删进 commit**（除非用户原始任务就是清理）
   - 你的改动**自己造成的**孤儿（imports / vars / functions）：删
   - **本来就在那里**的孤儿：mention，不删
   - 专门的死代码清理走 `refactor-cleaner` agent 或 `/refactor-clean` 命令

## 与现有规则的关系

| 关注点 | 文件 |
|---|---|
| 信息从哪来（先读哪个 doc） | [`docs-reading.md`](docs-reading.md) |
| 信息不够该怎么办（澄清 / push back / 停） | **本文件** |
| 重磅任务怎么对抗审查 | [`pipeline.md`](pipeline.md) |
| 写多简、不引入抽象 | system prompt |
| 不删 git 未知 state（分支 / 文件） | system prompt（`Executing actions with care` 节） |

## 落地检验

工作正确的信号：
- 模糊指令开工前先问 1-2 个澄清问题（不是事后 rework）
- "我看到 A、B 两种解读 / 做法，你要哪个？" 这种话频率上升
- 对复杂方案会指出"如果只是要 Y，简单点的做法是 Z"
- 改动 diff **没有**任务无关的"顺手 polish"

工作不到位的信号：
- 模糊任务直接开做，做完才说"我假设…"
- 看到 dead code 自己删了塞进 commit
- 用户给一个看起来绕的方案，照做不指出更简等价
