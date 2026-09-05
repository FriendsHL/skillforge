# Bailian Token Plan：Qwen3.8-Max 接入与 HappyHorse 可用性

日期：2026-09-05

状态：模型代码登记、运行服务加载及浏览器选择验证完成，新 Key 文本调用已验证；视频为已核实方案，未接入。

## 用户需求与实施范围

Ark Coding Plan 已过期，本月改订百炼；在 SkillForge 增加 qwen3.8-max，并核实
happyhorse-1.1-i2v 是否在订阅中可用。模型登记沿用既有 OpenAI-compatible/Qwen
协议；视频本次先调查，不把视频模型放入聊天模型列表。

## 方案

1. 在旧 bailian Coding Plan provider 直接追加模型：地址不匹配，不采用。
2. 覆盖旧 bailian 的 endpoint/key/model：会改变已有 Agent 的路由，不采用。
3. 新增 bailian-token-plan provider：采用。独立 BAILIAN_TOKEN_PLAN_API_KEY，
   专用 Token Plan endpoint，复用 OpenAiProvider、QWEN_DASHSCOPE 与既有模型 API。

核心变更仅为 ModelConfig 已知上下文表登记 1,000,000 tokens，防止回退到通用 Qwen
32K。后端模型列表和 vision allowlist、Dashboard fallback 同步增加新模型。
全局默认 provider 和既有 Agent 不因“新增模型”自动改写；拿到有效新 Key 后再验证实际调用。

## 验收

- 模型 API 返回 bailian-token-plan:qwen3.8-max，支持 thinking toggle 和 vision。
- 最终聊天地址是 https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions，
  不拼接重复 /v1，不回退到按量付费地址。
- 引擎已知模型窗口为 1,000,000，旧 Qwen 映射保持原值。
- Qwen thinking/tool-call/streaming 既有协议回归通过。
- 官方订阅范围与本机真实调用结果分开记录；未通过真实调用不宣称模型可用。

## 调研与接入状态

- 官方 Token Plan 个人版列表包含 qwen3.8-max、happyhorse-1.1-i2v、
  happyhorse-1.1-t2v 与 happyhorse-1.1-r2v。
- i2v 为图生视频，纯文生视频使用 t2v；视频走独立异步 API。
- 当前继承的 DASHSCOPE_API_KEY 对 Token Plan 的最小 qwen3.8-max 请求返回
  HTTP 401 / invalid_api_key。未提交视频生成任务，未验证本账号的视频权益。
- 用户已在 ~/.zshrc 配置 BAILIAN_TOKEN_PLAN_API_KEY；赋值空格错误已修正，source 退出 0。
  新 Key 对 qwen3.8-max 最小文本请求返回 HTTP 200 / OK（18 tokens）。个人/团队版类型仍未确认，
  流式、工具、图片和视频真实调用尚未验收。不在文档或代码保存 Key。

## 官方依据

- [Token Plan 个人版](https://help.aliyun.com/zh/model-studio/token-plan-personal-overview)
- [专用 Key 与 endpoint](https://help.aliyun.com/zh/model-studio/token-plan-personal-quick-start)
- [Qwen3.8-Max：能力与 1M 上下文](https://help.aliyun.com/zh/model-studio/qwen3-8-max)
- [Token Plan 多模态专用接口](https://help.aliyun.com/zh/model-studio/token-plan-multimodal-gen)
- [HappyHorse 首帧图生视频](https://help.aliyun.com/zh/model-studio/happyhorse-image-to-video-api-reference)

## 视频后续接入范围

套餐视频提交：POST /api/v1/services/aigc/video-generation/video-synthesis；
查询：GET /api/v1/tasks/{task_id}，均使用 token-plan.cn-beijing.maas.aliyuncs.com。
提交携带 X-DashScope-Async: enable，i2v 必须有一张首帧图。

当前 VideoGenerationProvider SPI、MediaJobStore、轮询下载和跨端播放器可复用；
MediaGenerationService 仍固定选择 ark，VideoRequest 没有首帧附件字段。因此完整接入需独立
百炼 adapter、provider 路由、首帧附件权限/物化以及状态和取消语义适配，不能只增加模型 ID。
Token Plan 以 Credits 结算，实际视频消耗和账号权益尚未验证。

## 验证记录

- 独立配置/协议审查 PASS：endpoint 透传、Key 隔离、1M 前缀优先级、vision/thinking 元数据一致；
  无 blocker/warning，真实调用门仍开放。
- RED：ModelConfigTest 在映射修改前失败，新模型落入 32,000 的通用 Qwen 窗口。
- GREEN：`mvn -pl skillforge-core,skillforge-server -am -Dtest=ModelConfigTest,ProviderProtocolFamilyResolverTest,OpenAiProviderThinkingTest,OpenAiProviderReasoningStreamTest,OpenAiProviderStreamToolCallTest,BailianTokenPlanConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test`
  共 39 tests、0 failures/errors/skips、BUILD SUCCESS。
- 配置测试加载生产 YAML，并通过 MockMvc 检查真实 Controller 的 JSON 模型列表、vision 和 thinking。
  此项是应用配置/契约验证，不是已运行服务或真实 Provider 验证。
- Dashboard `npx tsc --noEmit` 和 `npm run build` 退出 0；构建报告既有 chunk size warning。
- 浏览器打开当前 :3000 Agents → Create Agent → Model 搜索 qwen3.8，结果 No data；
  :8080 服务仍是 9 月 2 日启动的旧进程，尚未加载新配置。检查表单未保存。
- 未重启现有服务：工作区另有未交付 V196–198 与大量 runtime 改动，重启会连带加载这些变更；
  本次模型登记不顺带部署它们。新 Key 的文本调用已验证，仍需受控的服务加载与应用层验收。

## 用户授权重启后的验证（2026-09-05）

- 重启前完成数据库 custom-format 备份：`~/.skillforge/backups/pre-bailian-restart-20260905-141843.dump`。
- 首次启动暴露 SessionLoopLeaseHeartbeat 双构造函数缺少注入标记；新增 Spring 容器回归测试先复现失败，再以生产构造函数 `@Autowired` 修复。
- clean install 与模型/协议/注入定向回归共 40 tests，0 failures/errors/skips，BUILD SUCCESS。
- 首次非 clean 构建携带缓存 V199/V200，内容分别已合入源代码 V197/V196。核对源文件校验和、数据库约束及无效行数后，在事务内仅撤销两条冗余 Flyway 历史记录；没有回滚 DDL 或修改业务行。原 SQL、完整迁移历史与修复 SQL 归档至 `~/.skillforge/backups/restart-20260905-applied-migrations/`。
- clean jar 启动成功，Flyway 当前 V198、无待执行迁移；新进程已继承 BAILIAN_TOKEN_PLAN_API_KEY。
- 运行中 `GET /api/llm/models` 返回 HTTP 200 与 `bailian-token-plan:qwen3.8-max`，thinking/vision 为 true。
- Dashboard HTTP 200；真实浏览器 Agents → Create Agent → Model 搜索 qwen3.8 可见且可选中新模型，检查表单取消未保存。
- 既有 Agent 仍使用原 Ark 配置；用户试用时需选择新百炼模型。以上验证不涵盖本工作区其他需求的完整验收。


## 2026-09-06 追加需求（用户已批准实施与前后端重启）

状态：实现、自动化与真实浏览器验证完成，前后端已重启；真实生成受账号周额度耗尽限制。此前验收记录仅覆盖 Qwen3.8 初次接入。

### 范围与方案

- Token Plan 聊天模型列表包含 qwen3.8-max、qwen3.7-max、deepseek-v4-pro、deepseek-v4-pro-0813、deepseek-v4-flash-0731。
- 复用 bailian-token-plan 专用 endpoint/key，后端 API 与 Dashboard fallback 同步登记。
- 系统 Agent 在现有 Overview → Model 下拉框选择并保存模型。复用现有 PUT 更新接口，仅提交 modelId，避免提交其他只读字段。PUT 使用无默认值的 AgentUpdateRequest 保持部分更新语义；POST 创建默认值保持。
- 模型能力按百炼实际协议配置；DeepSeek 原厂与百炼的 thinking 方言需分别验证。
- 新增独立模型设置页或新 API 会重复既有能力，因此采用原位置局部开放；不整体开放系统 Agent 配置。
- 模型变更供后续运行读取，已有 Session 显式 runtime override 仍优先，不在运行中替换模型。

### 验收

- 五模型均在模型 API 和界面可见，thinking/effort/vision 与实际协议一致。
- 系统 Agent 模型可选择、保存、重新打开后保留；其他只读字段保持原限制。
- 保存请求仅包含 modelId，失败保留可重试状态；已有用户 Agent 编辑流程回归通过。
- 重启前后端后服务可用，新模型列表已加载，已保存模型配置保留。
- 定向自动化测试、真实浏览器选择/保存与 API 持久化核查；实际 Provider 调用单独记录结果。

官方参考：[Token Plan 模型列表](https://help.aliyun.com/zh/model-studio/token-plan-personal-overview)、[百炼 DeepSeek 协议](https://help.aliyun.com/zh/model-studio/deepseek-api)。


### 2026-09-06 验证与重启记录

- Full 独立审查：功能符合范围，无 blocker；保守上下文与账号额度限制见下。
- 主线程 clean install + 模型/协议/Agent 定向回归：100 tests，0 failures/errors/skips，BUILD SUCCESS。覆盖 OpenAI thinking、reasoning SSE、tool-call/replay、Claude cache/error 及 Agent 更新/筛选。
- Dashboard 主线程回归：5 files，29 passed / 5 既有 skipped；TypeScript 检查与生产构建退出 0，保留既有 chunk size warning。
- 真实 Chromium：临时系统 Agent 验证模型可选、PUT 仅 modelId、保存后 Saved/disabled、重新打开持久化；重启后临时 Agent 模型保持。随后核查发现 Entity 创建默认值污染 PUT 的 status/executionMode/mcpServerIds，新增无默认值更新 DTO 修复，并追加接口及浏览器验收。
- 重启前数据库备份：`~/.skillforge/backups/pre-model-settings-20260906.dump`。
- 最终后端新 PID 16498，前端 Vite 新 PID 16517；8080 模型 API 和 3000 Dashboard 均返回 HTTP 200。Flyway V198，无新迁移。
- 重启后模型 API 返回指定五模型，vision 仅 qwen3.8-max 为 true；真实浏览器五模型可检索，并成功保存 deepseek-v4-pro-0813。
- 真实 Provider 最小调用：五模型均 HTTP 429；追加读取一次 DeepSeek 错误确认为 `insufficient_quota`，周额度耗尽，返回重置时间 2026-09-12 06:17 UTC（北京时间 14:17）。没有宣称上游生成验收通过。
- 百炼 DeepSeek 思考字段按精确 Token Plan host 适配为 enable_thinking；原厂 thinking.type 不变。medium effort 映射 high，旧 Pro 的 low 映射 high，0813/0731 保留 low。
- 上下文限制：Qwen3.7/3.8 已知窗口 1M；DeepSeek 沿用引擎保守窗口，Pro/0813 为 128K，Flash 回退 64K。CompactionService 仍读取既有 provider YAML 的 1M，两处策略未在此次统一；不宣称五模型均启用 1M。未将价格表中的免费额度误当上下文依据。
- 本次未自动替换现有业务系统 Agent 的模型；用户可在 System Agents → Agent → Overview → Model 选择保存。

- 最终补充修复：AgentUpdateRequest 覆盖既有 21 个可更新字段，Service 单独保留 MCP 字段存在性，避免 setter 的 null→空串归一清空原配置；独立二轮审查 PASS。真实 JSON 回归覆盖 model-only、null 保留、显式空串、false 写入、普通参数更新和创建默认值。
- 最终修正版重启后，真实浏览器保存 qwen3.7-max，API 与 SQL 均确认 modelId 更新且 inactive / auto / 原 MCP 配置保持；重新打开仍一致。临时验证 Agent 已通过 API 删除，并以 SQL 确认清理。
