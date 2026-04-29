# Pipeline Meta

> 元工作内容：流水线演进观察、ROI 判断、迁移到新项目。
>
> **本文件不自动加载**，只在维护 [`pipeline.md`](pipeline.md) 本身（调约束、加红灯触发器、迁移给新项目）时再读。日常开发任务不需要看。

---

## 一、流水线演进观察（post-mortem）

**第一次跑新 pipeline 或改动约束后做一次 post-mortem**，记录：

- 哪些 blocker 是 self-check 自己就能挡的 → 调约束 A 强度
- Reviewer severity 错判率 → 调约束 B checklist 细度
- Nit 是否真的是 nit → 调约束 C 阈值
- 循环触顶（2 轮用完）频率 → 考虑调上限或入口门槛
- Mid → Full 升级触发率 → 如果偏高（>30%），说明 Mid 入口门槛太松，红灯条件需要扩

**日常任务不写 post-mortem**。Commit message 只写代码变更本身，不带 pipeline 元数据。

---

## 二、Pipeline ROI 判断

Pipeline review 的价值 = **catch rate × impact**。

- **高价值场景（→ Full）**：触碰核心引擎、并发、事务、schema migration、新协议 —— 单次运行就能防 3-8 个生产 bug，值
- **中等价值场景（→ Mid）**：单/双模块改动、加字段、新依赖、UI 行为变更 —— Mid 的 1 轮对抗 review 能抓非平凡 bug，不需要循环
- **低价值场景（→ Solo）**：单文件 bug 修复 / rename / docs / UI polish —— 跑 pipeline 是纯浪费

用红灯 / Mid / Solo 就是在显式选 ROI。

**演进路径**：
- 2026-04-15 之前：按红黄绿灯判断（默认策略）
- 2026-04-15 → 2026-04-30：所有非 trivial 强制 Full（用 token 买"漏检 0%"）
- 2026-04-30 起：红灯 Full / 默认 Mid / 例外 Solo（用 Mid 的 1 轮对抗换效率，红灯保留 Full 深度）

---

## 三、迁移到新项目（~2 分钟）

第一次在新项目用这套框架：

1. `cp skillforge/.claude/rules/pipeline.md <新项目>/.claude/rules/pipeline.md`
   - 可选：`cp skillforge/.claude/rules/pipeline-meta.md <新项目>/.claude/rules/pipeline-meta.md`（如果新项目也想保留元工作记录）
2. 改 **`pipeline.md` 第二部分「项目策略」** 三处：
   - **强制级别**：选哪种策略
     - "按红黄绿灯判断"（默认 / Light 项目）
     - "红灯 Full / 默认 Mid / 例外 Solo"（SkillForge 策略 / 重要项目）
     - "所有任务强制 Full"（最保守 / 高价值核心系统）
   - **核心文件清单**（~5-10 个）—— 列出"如果这里写错整个系统关键功能会炸，浏览器/curl 测不出来"的文件
   - **Known footguns 清单** —— 踩过的坑，第一条通常在第一次踩坑后立刻加
3. 改 **第四节「子 agent 模型选择策略」**：
   - 默认（其他项目）：全 Sonnet
   - SkillForge 策略：Reviewer + Planner Sonnet / Dev + Judge Opus
   - 最保守：全 Opus
4. 其余部分（第一、三、五、六节）直接复用，一般不需要改

**通用框架走 `pipeline.md` 第一/三/四/五/六节，项目特化数据走第二节，元工作走 `pipeline-meta.md`** —— 规则归规则、数据归数据、元工作归元工作。
