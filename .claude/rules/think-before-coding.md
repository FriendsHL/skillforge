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
