# SkillForge 文档治理规范

> 更新于：2026-04-28
> 范围：`docs/` 下的需求、方案、索引、交付记录和归档规则。

## 目标

SkillForge 使用 Markdown 作为需求、技术方案、交付证据和项目记忆的事实来源。文档维护要同时服务两类读者：

- 人能快速看到当前状态，不需要翻长历史。
- Agent 只读取当前任务必要上下文，避免浪费 token。
- 历史方案和评审材料要可追溯，但不能污染当前工作入口。

## 顶层职责

| 文件 / 目录 | 职责 |
| --- | --- |
| `docs/README.md` | 文档总入口和阅读导航。 |
| `docs/todo.md` | 当前执行面板，只放排期和状态。 |
| `docs/delivery-index.md` | 已完成交付事实：日期、commit、migration、验证、关联文档。 |
| `docs/requirements/active/` | 当前或近期会进入设计 / 实现的需求包。 |
| `docs/requirements/backlog/` | 值得保留但未进入近期队列的需求。 |
| `docs/requirements/deferred/` | V2 或明确暂缓的需求。 |
| `docs/requirements/archive/` | 已完成需求包。 |
| `docs/references/` | 长期参考资料、provider 踩坑录、外部调研、视觉参考。 |
| `docs/operations/` | 运维手册、灰度手册、脚本。 |

## 需求包

每个有意义的需求都放在独立目录中。默认使用最轻量但足够安全的模式。

## 文档模式

### Lite 需求

当以下条件全部成立时，只需要一个 `index.md`：

- 范围小且清楚。
- 风险是 `Solo`。
- 不涉及 schema、API、协议、持久化、核心不变量。
- 不涉及跨前后端行为变化。
- 不需要多轮产品澄清。

典型例子：

- 纯文档整理。
- 小文案或小样式调整。
- 配置常量调整。
- 行为明确、验证集中的窄 bugfix。

Lite 目录：

```text
docs/requirements/active/<ID>-<slug>/
  index.md
```

Lite 的 `index.md` 必须包含：用户请求、验收点、实现说明、验证方式。

### Full 需求

命中以下任一条件时，使用完整三件套：

- 风险是 `Full`。
- 触碰核心文件或跨前后端行为。
- 新增或修改 schema、REST API、协议处理、持久化、权限、序列化、长任务流程。
- 用户意图还需要澄清。
- 存在有意义的方案取舍。
- 需要对抗评审。

Full 目录：

```text
docs/requirements/active/<ID>-<slug>/
  index.md
  mrd.md
  prd.md
  tech-design.md
  implementation-plan.md    # 可选，技术方案批准后才需要
  reviews/                  # 可选
```

Full 模式三件套职责：

| 文档 | 来源 | 目的 |
| --- | --- | --- |
| `mrd.md` | 用户原始诉求 | 保留用户需求、背景、痛点、限制和未澄清问题。 |
| `prd.md` | 用户 + Agent 澄清结果 | 明确产品目标、非目标、工作流、功能需求、验收标准和验证预期。 |
| `tech-design.md` | Agent + 对抗评审 | 记录技术方案、关键决策、替代方案、实现拆分、风险和测试计划。 |

`index.md` 是需求包入口，只放摘要、状态、阅读顺序和下一步。

历史 `design-*.md` 不再作为独立入口维护。已交付需求的技术方案统一并入 `requirements/archive/<yyyy-MM-dd>-<ID>-<slug>/tech-design.md`；无法归属到单个需求的长期资料放入 `references/`。

## 状态模型

| 状态 | 含义 |
| --- | --- |
| `mrd` | 已捕获用户诉求，产品需求尚未澄清。 |
| `prd-draft` | 正在澄清产品行为。 |
| `prd-ready` | PRD 已确认，可进入技术方案。 |
| `design-draft` | 技术方案草稿中。 |
| `design-review` | 技术方案对抗评审中。 |
| `design-approved` | 技术方案已通过，可实现。 |
| `in-progress` | 已开始实现。 |
| `done` | 已交付，并已写入 `delivery-index.md`。 |
| `deferred` | 明确暂缓。 |
| `dropped` | 明确不做。 |

## `todo.md` 格式

`docs/todo.md` 是当前执行面板，必须短，方便人和 Agent 快速读完。

推荐结构：

```md
# SkillForge ToDo

> 更新于：YYYY-MM-DD
> 规则：这里只放当前执行状态；需求和方案细节放在链接文档中。

## 当前队列

| 顺序 | ID | 标题 | 模式 | 状态 | 优先级 | 风险 | 文档 | 下一步 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |

## 阻塞 / 待决策

| ID | 待决策 | 负责人 | 阻塞项 |
| --- | --- | --- | --- |

## 最近完成

| ID | 完成日期 | Commit | 交付索引 |
| --- | --- | --- | --- |

## 暂缓

见 `docs/requirements/deferred/`。
```

规则：

- 不把长方案、事故长复盘、评审全文放进 `todo.md`。
- 当前队列每一行都应链接到需求包。
- `模式` 只能是 `Lite` 或 `Full`。
- `风险` 只能是 `Solo` 或 `Full`。
- `todo.md` 尽量控制在 150 行以内。超过说明细节该移入需求包。
- 完成事项短暂停留在“最近完成”，完整证据写入 `delivery-index.md`。

## `docs/README.md` 格式

`docs/README.md` 是文档总入口，也是 Agent 的默认阅读导航。

推荐结构：

```md
# SkillForge 文档

> 更新于：YYYY-MM-DD
> Agent 规则：先读这里，再只打开当前任务链接到的文档。

## 从这里开始

| 需求 | 阅读 |
| --- | --- |

## 当前需求

| ID | 标题 | 状态 | 需求包 | MRD | PRD | 技术方案 | 交付 |
| --- | --- | --- | --- | --- | --- | --- | --- |

## 已交付方案

| ID | 标题 | 方案 | 交付 |
| --- | --- | --- | --- |

## 归档规则
```

规则：

- README 只做导航，不做内容堆放。
- 当前需求必须链接到需求包。
- 已交付方案必须能找到交付证据。
- `archive/`、需求包内 `reviews/`、长参考资料默认不读，只有任务需要时才读。

## Agent 阅读顺序

做需求或实现任务时：

1. 读 `docs/README.md`。
2. 读 `docs/todo.md`，只用于确认当前队列和需求链接。
3. 打开需求包 `index.md`。
4. 实现前必须读 `prd.md`。
5. 技术方案已确认或正在实现时读 `tech-design.md`。
6. 只有产品意图、原始限制或歧义不清楚时才读 `mrd.md`。
7. 默认不读 `reviews/`、`archive/` 或长参考文档，除非需求包明确要求或用户指定。

## 归档规则

需求交付后：

1. 将需求包状态改为 `done`。
2. 从 `requirements/active/` 移到 `requirements/archive/<yyyy-MM-dd>-<ID>-<slug>/`，日期使用交付日期，放在目录名前缀以便排序。
3. 更新 `delivery-index.md`。
4. `todo.md` 只保留一条短完成摘要。
5. 评审材料放进需求包的 `reviews/`。

需求暂缓时：

1. 将状态改为 `deferred`。
2. 移到 `requirements/deferred/`。
3. 在 `index.md` 和 `prd.md` 里写清重评触发条件。

## 写作规则

- 优先链接，不复制粘贴重复内容。
- 摘要放前面，越短越好。
- 长背景放 `mrd.md`，不要放 `todo.md`。
- 验收标准放 `prd.md`，不要放 `tech-design.md`。
- 实现细节放 `tech-design.md`，不要放 `prd.md`。
- 交付证据放 `delivery-index.md`，不要只留在聊天或 commit message 里。
- ID 必须稳定，并在所有文档里一致。
- 一个需求包只描述一个需求，不混批。
