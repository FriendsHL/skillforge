# Docs Reading Rules

> 给 Claude 的 `docs/` 阅读顺序。目的：拿任务上下文时只读必要文档，不扫历史归档浪费 token。
>
> 文档治理规范本身在 [`docs/DOCS-GOVERNANCE.md`](../../docs/DOCS-GOVERNANCE.md)；本文件只规定**作为读者**怎么读。

## 标准阅读顺序

非 trivial 的实现 / 审查 / 设计任务，按这个顺序拿上下文：

1. **`docs/README.md`** — 文档总入口。从这里找到当前任务对应的需求包链接。
2. **`docs/todo.md`** — 只用来确认队列、优先级、阻塞、和需求包链接。**不要把 todo.md 当需求详情来源**。
3. **需求包 `index.md`** — 需求包入口。指向当前任务真正要看的 MRD / PRD / tech-design。
4. **`prd.md` + `tech-design.md`** — 实现前必读，定义范围、验收点、技术方案。
5. **`mrd.md`** — **仅在**用户原始意图、约束或未解决的产品问题不清楚时才读。意图清楚时不要读，浪费 token。

Lite 需求例外：如果需求包只有 `index.md`（含 user request / acceptance / impl notes / verification），读完 index 就够。

## Archive 不默认扫

`docs/requirements/archive/<yyyy-MM-dd>-<ID>-<slug>/` 下的归档需求包**不默认读**。只在以下情况打开：

- 当前需求包文档显式链接到归档需求
- 改动的代码区域依赖某个归档需求的方案
- 用户显式要求

不要因为"想了解项目历史"就把 archive 翻一遍。

## 完成事实以 delivery-index 为准

涉及"这个需求做完了没 / 哪个 commit 上线 / 哪条 migration 跑过 / 实际交付范围"的问题，**以 [`docs/delivery-index.md`](../../docs/delivery-index.md) 为准**，而不是猜 git log 或翻 archive 的 index。

## 冲突仲裁

如果 `docs/README.md`、`docs/todo.md`、需求包三处对同一个需求描述不一致：

- 范围 / 验收点 / 技术细节冲突 → 以**需求包**（prd.md / tech-design.md）为准
- 已完成事实冲突 → 以 **`delivery-index.md`** 为准
- 状态（active / backlog / deferred / archive 归属）冲突 → 以 README.md 当前表格为准

发现不一致时**实现前先 flag 给用户**，不要默默选一个继续。

## 速查表

| 问题 | 读哪个 |
|---|---|
| 当前要做什么？ | `docs/todo.md`（队列） + `docs/README.md`（需求包入口） |
| 这个需求要做成什么样？ | 需求包 `prd.md` + `tech-design.md` |
| 用户为什么要这个需求？ | 需求包 `mrd.md`（仅当意图不清时） |
| 这个需求做完了吗？哪个 commit？ | `docs/delivery-index.md` |
| 历史上 X 是怎么实现的？ | `docs/requirements/archive/...`（仅当当前需求链接到，或用户指定） |
| 文档怎么组织、怎么写？ | `docs/DOCS-GOVERNANCE.md`（**作者视角**，不是读者视角） |
