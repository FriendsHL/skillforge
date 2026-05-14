# MULTIMODAL-MVP 技术方案

---
id: MULTIMODAL-MVP
status: design-draft
prd: ./prd.md
risk: Full
created: 2026-05-13
updated: 2026-05-14
---

## TL;DR

新增 session attachment 入口，把 Chat 上传的图片/PDF保存为受控本地文件，并在 `t_session_message.content_json` 中只持久化 `image_ref` / `pdf_ref` 引用。实际 base64 图片或 PDF page image 只在 provider 请求边界、基于最终生效的 provider/model materialize。provider 层补 capability 判断和 OpenAI-compatible image block 发送支持，禁止普通用户多模态消息被 silently dropped，同时保留 tool_result replay 的防御性过滤语义。**Agent 实体新增 `multimodalModelId` 字段（与主 `modelId` 并列、nullable）**，用作含附件 turn 的 effective model；Chat 上传按钮按该字段是否非空 gate。

## 现状证据

- Chat 页面当前没有通用文件上传入口。
- 现有 message content 主要是 `String` 或 `ContentBlock` list，已能承载结构化 block，但图片 block 发送链路未打通。
- `OpenAiProvider` 当前对 image / tool_use 等 blocks 有 silently dropped 行为，vision 模型能力无法使用。
- `Chat.tsx` 当前无 session 时在发送文本时才创建 session；附件上传必须定义新建 Chat 的 session 生命周期。
- `ChatWindow.tsx` 当前空文本不能发送；本包要求纯附件消息合法。
- `FileReadTool` 不识别 image / PDF / notebook；但本包不以 FileRead 为主入口，Chat attachment 是产品闭环入口。
- 项目当前无 PDFBox / POI / EasyExcel 等文档解析依赖。

## 风险分级

本包是 Full：

- 触碰 Chat UI。
- 新增 REST/upload + attachment 持久化或文件存储。
- 触碰 core LLM provider/content block 协议。
- 涉及文件安全、大小限制、provider capability、trace 可观测。
- 跨 dashboard / server / core 多模块。

## 推荐架构

```text
AgentDrawer.tsx
  -> primary model picker (existing)
  -> multimodal model picker (NEW, optional)
  -> save -> PATCH /api/agents/{id} { modelId, multimodalModelId }

Chat.tsx
  -> read activeAgent.multimodalModelId
  -> attachment button: enabled iff multimodalModelId is non-empty
        (disabled state shows tooltip + link to agent config)
  -> if no activeSessionId and user selects file: create draft session
  -> upload attachment(s) to active session
  -> send message with text + attachmentIds

AttachmentController
  -> AttachmentService
      -> validate file type/size/magic bytes
      -> store bytes under controlled data dir
      -> create AttachmentEntity

ChatService.chatAsync
  -> load pending attachments
  -> build persisted Message.user([... text, image_ref/pdf_ref blocks ...])
  -> append user row
  -> bind attachment(s) to (session_id, seq_no)
  -> AgentLoopEngine

AgentLoopEngine / provider boundary
  -> resolve effective model:
        if current turn message contains image_ref/pdf_ref AND agent.multimodalModelId is set
          -> use agent.multimodalModelId
        else
          -> use agent.modelId (with existing /model override path)
  -> AttachmentRequestMaterializer
      -> image_ref -> provider image block if model supports vision
      -> pdf_ref -> text block or page image block after capability check
  -> capability check
        -> if effective model is multimodalModelId but model still does not support vision
              -> throw MULTIMODAL_MODEL_NO_VISION_CAPABILITY (Ratify #9, no silent fallback)
  -> serialize image blocks correctly
  -> explicit unsupported error
```

## Ratified 技术决策

| 决策 | 结论 | 理由 |
| --- | --- | --- |
| 新 Chat 上传生命周期 | 选择附件时先创建 draft session，再上传到 `/api/sessions/{sessionId}/attachments` | 复用现有 session ownership，不引入无 session 临时文件池；比 multipart send 更贴近当前 async chat flow |
| message content 表达 | 持久化 `image_ref` / `pdf_ref`，不持久化 base64 | 避免 `t_session_message` 膨胀，replay 与 compact 能保留稳定引用 |
| provider bytes 生成位置 | provider-boundary materialization，在最终 provider/model resolved 后执行 | `/model` override 在 `runLoop` 内生效；ChatService 提前判断会用错模型能力 |
| attachment 绑定 | 绑定到 `(session_id, seq_no)`，不绑定 generated `session_message.id` | `appendMessages` 当前只返回 last seq_no；`(session_id, seq_no)` 已有唯一约束，能原子推导本次 user row |
| observability | Phase 1 记录 attachment metadata 到 request context/span attrs；raw payload base64 默认 redacted | 避免 raw LLM payload blob 复制图片大字段和敏感 PDF 全文 |
| **agent 多模态模型独立配置** | `t_agent` 加 `multimodal_model_id VARCHAR(64)` 列，nullable；与 `model_id` 并列存在 | 让上传能力与主对话模型解耦，避免把主模型强制升级到 vision-capable；含附件 turn 单独切模型，不影响纯文本 turn 的 cache 稳定 |
| **上传按钮 gate 仅看 agent 配置** | FE 仅读 `agent.multimodalModelId` 是否非空；不发探测请求；BE 在 materializer 严格 capability 校验 | 避免点击一次就发 capability 探测请求；FE/BE 责任分明：FE 控 UX 入口，BE 控 effective model 正确性 |
| **多模态 turn effective model 路径** | 当 user message 包含 image_ref / pdf_ref 时，`ChatService.runLoop` 把 effective `modelId` 替换为 `agent.multimodalModelId`；该选择优先于 `/model` runtime override 仅对该 turn 生效 | `/model` 是用户临时切，多模态附件是结构性切换；优先级"附件存在 > /model override > agent.modelId" 在 PRD Ratify #7 落地 |
| **multimodalModelId 未配置时附件 endpoint 拒收** | `POST /api/sessions/{sessionId}/attachments` 入参校验 `agent.multimodalModelId` 非空；空则返 `409 MULTIMODAL_MODEL_NOT_CONFIGURED` | FE gate 是一层防御，BE 必须独立校验防绕过；agent 中途清掉 multimodalModelId 时已上传未发送的 attachment 仍能 cleanup |

## 数据模型

### `t_agent` 新增列

```sql
ALTER TABLE t_agent ADD COLUMN multimodal_model_id VARCHAR(64);
```

- nullable，默认 NULL；不需 backfill。
- migration 编号建议 `V71__add_agent_multimodal_model_id.sql`（V70 已被 `chat_attachments` 占用，工作树已有该文件）。
- `AgentEntity` 加字段 + getter/setter，`AgentDTO` / 创建-更新 request body 同步加 `multimodalModelId: string | null`。
- 不加外键到 model 表（项目无 `t_model` 表，model 是 yaml 配置）。

### `t_session_attachment`

新增 attachment 表（工作树 V70 已建 `t_chat_attachment`，本文档术语统一沿用工作树命名）。字段建议：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | varchar(36) | UUID |
| `session_id` | varchar(36) | 所属 session |
| `message_seq_no` | bigint nullable | 发送后绑定 user message 的 seq_no；发送前可为空 |
| `user_id` | bigint | 上传者 |
| `original_filename` | varchar(255) | 展示用，必须 sanitize |
| `mime_type` | varchar(100) | 后端检测结果 |
| `file_size_bytes` | bigint | 文件大小 |
| `storage_path` | varchar(500) | 受控 data dir 下相对路径 |
| `kind` | varchar(30) | `IMAGE` / `PDF` |
| `status` | varchar(30) | `UPLOADED` / `PROCESSED` / `FAILED` |
| `processing_mode` | varchar(50) | `IMAGE_BLOCK` / `PDF_TEXT` / `PDF_PAGE_IMAGE` |
| `page_count` | int nullable | PDF 页数 |
| `extracted_text_chars` | int nullable | PDF 文本长度 |
| `error_code` | varchar(80) nullable | 失败分类 |
| `error_message` | text nullable | 用户可读错误 |
| `created_at` / `updated_at` | TIMESTAMPTZ | 使用 `Instant` |

约束和索引：

- migration 使用下一个版本 `V70__session_attachments.sql`。
- FK：`session_id -> t_session(id) ON DELETE CASCADE`。
- 唯一绑定约束：可选 `UNIQUE(session_id, message_seq_no, id)`；查询主要依赖 `(session_id, message_seq_no)`。
- 索引：`(session_id, status)`、`(session_id, message_seq_no)`、`(user_id, created_at)`。
- `storage_path` 只存服务生成的相对路径，例如 `{userId}/{sessionId}/{uuid}.bin`，不接受用户路径片段。
- orphan cleanup：`UPLOADED` 且 `message_seq_no IS NULL` 超过 24h 可由后续 cleanup job 清理；Phase 1 至少提供 repository 查询和 service 方法。

文件存储先用本地 data dir，配置项建议为 `skillforge.attachments.storage-root`。后续对象存储另开需求。

## API 设计

建议新增：

- `POST /api/sessions/{sessionId}/attachments`
  - multipart upload。
  - 参数：`userId` 沿用当前项目模式。
  - **入参校验**：找到 session 对应 agent，断言 `agent.multimodalModelId` 非空；空则返 `409 { code: "MULTIMODAL_MODEL_NOT_CONFIGURED" }`。
  - 返回 attachment metadata。
- `GET /api/sessions/{sessionId}/attachments`
  - 用于 session detail / 回放。
- 现有发送消息 endpoint request body 增加可选 `attachmentIds: string[]`。
- 不新增无 session upload endpoint；新 Chat 选择附件前，前端调用现有 create session 创建 draft session。

### Agent CRUD

- `POST /api/agents` / `PATCH /api/agents/{id}` request body 增加可选 `multimodalModelId: string | null`。
- 不做白名单校验（允许任意 model id；不支持 vision 的 model 在 BE materializer 处显式报错，落到 PRD Ratify #9）。
- 返回体携带 `multimodalModelId`。

发送时校验：

- attachment 必须属于同一 session / user。
- attachment 必须 `message_seq_no IS NULL` 且 status 允许发送。
- 文本可为空，但 `trimmedText` 和 `attachmentIds` 不能同时为空。
- ChatService append user row 后得到 last seq_no；本次 user row seq_no 即 last seq_no（单条 user append）或 `lastSeq - appendedCount + userIndex`，然后在同一 service 流程内绑定 attachments。
- 若 attachment processing 成功但 append 失败：attachment 保持 `UPLOADED` 且未绑定，前端仍可重试发送或移除。
- 若 append 成功但 loop start 失败：attachment 已绑定，session message 保留，错误按现有 runtimeError/WS 路径展示。

## ContentBlock 扩展

当前 `ContentBlock` 只有 `text` / `tool_use` / `tool_result`。Phase 1 增加两类持久化引用 block 和一类 provider 内部 materialized block：

```json
{
  "type": "image_ref",
  "attachment_id": "uuid",
  "mime_type": "image/png",
  "filename": "screenshot.png"
}
```

```json
{
  "type": "pdf_ref",
  "attachment_id": "uuid",
  "filename": "paper.pdf",
  "page_count": 12
}
```

Provider-boundary materialization 后的临时 block 不写 DB：

```json
{
  "type": "image",
  "mime_type": "image/png",
  "data_base64": "<base64>"
}
```

Provider 序列化层可以决定把内部 representation 转成目标 API 格式：

- OpenAI-compatible：`{"type":"image_url","image_url":{"url":"data:image/png;base64,..."}}` 或 provider 要求的等价格式。
- Anthropic：`{"type":"image","source":{"type":"base64","media_type":"image/png","data":"..."}}`。

Jackson 兼容要求：

- `ContentBlock` 保持 no-arg constructor。
- 未知字段必须被容忍；旧消息无 `attachment_id` 不受影响。
- `Message.getTextContent()` 对 `image_ref` / `pdf_ref` 可返回安全占位文本，例如 `[Attachment: screenshot.png]`，但 provider 请求不能依赖该占位文本理解图片。
- compact/replay 持久化仍保留 `image_ref` / `pdf_ref`，不得把其替换成 base64。

## PDF 处理

建议引入 Apache PDFBox：

- 文字抽取：`PDFTextStripper`。
- 页数读取：限制最大页数。
- page image fallback：`PDFRenderer` 渲染前 N 页为图片。

策略由 `AttachmentRequestMaterializer` 在最终 provider/model resolved 后执行：

1. 抽取文本长度 >= 最小阈值：作为 text block 注入。
2. 抽取文本不足：
   - provider 支持 vision：渲染前 N 页为 image blocks。
   - provider 不支持 vision：返回 `PDF_TEXT_EMPTY_NEEDS_VISION`。
3. 文本超限：截断并附 `[PDF text truncated ...]`。
4. 加密 PDF、页数超限、渲染异常必须变成用户可读 error code，不 silent fail。

## Provider Capability

新增或扩展 provider/model metadata：

```java
boolean supportsImageInput(String model);
```

初始可用配置白名单：

- OpenAI-compatible：按 provider/model 配置 `supports-image-input: true`，不能只按 provider 粗判。
- Claude：若已有 provider，实现对应 capability。
- 默认 false。

### Effective model 选择（含附件 turn）

`ChatService.runLoop` 当前已有 `/model` runtime override 路径（`t_session.runtime_model_override`）。本包增加附件感知：

```
boolean hasMultimodalBlocks = userMessageContent.anyMatch(b -> b.type == "image_ref" || b.type == "pdf_ref");
String effectiveModelId =
    hasMultimodalBlocks && StringUtils.hasText(agent.multimodalModelId)
        ? agent.multimodalModelId
        : (session.runtimeModelOverride != null ? session.runtimeModelOverride : agent.modelId);
```

- 优先级：**附件存在 + multimodalModelId 配置 > /model runtime override > agent.modelId**。
- 多模态 turn 的 cache 与主对话隔离；后续纯文本 turn 回到主 model，cache 不污染（Ratify #7）。
- effective model 选择结果写入 trace span attr `llm.effective_model` + `llm.model_source`（`"agent.multimodalModelId"` / `"session.runtimeModelOverride"` / `"agent.modelId"`），便于排查。

### 发送前/序列化校验

- 普通 user message 中有 `image_ref` materialized 成 image block，但 effective model 不支持 image input -> 抛 `MULTIMODAL_MODEL_NO_VISION_CAPABILITY`，不 silent drop、不 fallback 到主 model（PRD Ratify #9）。
- OpenAI-compatible regular user message 的 block list 要序列化为 content array，不能再降级到 `msg.getTextContent()` 导致 image 消失。
- tool_result replay 路径保留现有防御性过滤：混有 image/unknown block 和 tool_result 时，只允许合法 `tool_result` 进入 role=tool；这不是普通多模态 user message。
- provider serialize 遇到未知 regular user block -> 抛错，不 silent drop。

## 前端设计

### AgentDrawer / AgentEditor

- 主 `Model` 选择器（已有 `modelIdDraft`）旁新增 `Multimodal model`（可选）选择器。
- 复用 `useLlmModels` 已有 options，不做二次过滤。
- 状态：`multimodalModelIdDraft: string | undefined`；脏检查纳入 `hasUnsavedChanges`。
- 保存时透传到 `PATCH /api/agents/{id}` body 的 `multimodalModelId` 字段。
- UI 文案提示："仅在用户发送带图片/PDF 的消息时使用；不影响主对话。"

### Chat 输入区附件按钮

- 支持拖拽或点击选择可以后续再做；Phase 1 点击选择即可。
- **按钮启用 gate**：仅当 `activeAgent?.multimodalModelId` 非空时启用；为空时按钮 `disabled={true}` + Ant Design `Tooltip` 显示 "请先在 agent 配置中选择多模态模型"，tooltip 内嵌 `<Link>` 跳转到 `/agents?openAgentId={agentId}&tab=overview`（AgentList 已支持 `openAgentId` query 自动打开 drawer，本包补 `tab` 参数行为）。
- 附件 chip 显示文件名、类型、大小、上传/处理状态、移除按钮。
- 不支持格式即时提示，但以后端校验为准。
- 发送中禁用重复上传/发送。
- agent 回复区域不做复杂预览，先显示附件 chip 和处理状态。
- 新建 Chat 中首次选择附件：若已选 agent 且 `multimodalModelId` 非空，则先创建 session，再上传；未选 agent 时提示先选 agent；已选 agent 但 `multimodalModelId` 为空时按钮保持禁用（同 gate 逻辑）。
- `ChatWindow` 的 `onSend` 契约改为 `onSend(text, attachmentIds)`；发送按钮启用条件改为 `trimmedText || uploadedAttachmentIds.length > 0`。
- attachment 状态机：`selected -> uploading -> uploaded -> send-pending -> bound | failed | removed`。
- 上传请求 BE 返 `409 MULTIMODAL_MODEL_NOT_CONFIGURED` 时（gate 绕过场景），FE 把该 attachment 标 `failed` 并显示同样的 tooltip 文案 + 跳转链接。
- session 切换时清理未发送的本地 selected/uploading 状态；已上传未绑定 attachment 留给 24h cleanup。
- 现有 Voice 按钮保持非本包能力，不因为附件 UI 变更暗示音频已支持。

## 安全与限制

初始限制建议：

- 单文件最大 20MB。
- 单消息最多 5 个附件。
- PDF 最多 20 页；page image fallback 最多前 5 页。
- 图片压缩到 provider 安全尺寸，例如长边不超过 2048。
- MIME + magic bytes 双校验。
- 文件存储目录禁止路径穿越。
- 原始文件下载 endpoint 如非本包必需，先不暴露。
- upload endpoint 先完成 session ownership 校验，再读取/落盘 bytes。
- sanitize/truncate filename 后才进入 DB、trace metadata、UI。
- raw LLM payload blob 可能包含 PDF 文本或 image data；Phase 1 默认在 trace raw request 中 redacted image base64，只保留 attachment id / mime / size。
- SkillSecurityScanner 不扫描用户附件；本包不宣称 attachment malware scanning。
- PDFBox 渲染设置最大 decoded pixels，防 pixel bomb / decompression bomb；加密 PDF 直接拒绝。

## Trace / 可观测

Phase 1 必须接入 context/observer 或降低验收。选择接入：

- attachment ids。
- sanitized filename。
- kind / mime / size。
- processing mode。
- extracted text chars。
- page image count。
- provider capability result。
- fallback/error code。

实现要求：

- 扩展 `LlmCallContext` 或 engine/provider observer path，允许传递 request attrs。
- `TraceLlmCallObserver` 合并 `ctx.attributes()` 到 span attrs。
- raw request blob 中 image base64 默认 redacted；PDF 抽取文本按现有 payload 策略可能进入 request blob，需在文档和 UI 中视为敏感 trace artifact。
- 不在日志里打印 PDF 全文或 base64 图片。

## 实施拆分

1. **设计评审前置**
   - 已 ratify：attachment 落 DB + local data dir。
   - 已 ratify：message content 存 `image_ref` / `pdf_ref`，不存 base64。
   - 已 ratify：provider/model capability 按 model 配置。
   - 已 ratify：`t_agent.multimodal_model_id` 字段 + 上传 gate + effective model 切换。
2. **Agent 配置改动**
   - V71 migration 加 `t_agent.multimodal_model_id`。
   - `AgentEntity` / DTO / controller / service / test 加字段。
   - AgentDrawer 加 multimodal model picker。
3. **后端 attachment 基础**
   - migration + entity/repository/service/controller。
   - upload endpoint 校验 `agent.multimodalModelId` 非空。
   - 文件校验和本地存储。
4. **PDF/image materializer**
   - 图片压缩和 block 构造。
   - PDFBox 文本抽取和 page image fallback。
5. **ChatService 集成**
   - send message 接 `attachmentIds`。
   - user message content block 构造为 reference blocks。
   - append 后按 `(session_id, seq_no)` 绑定 attachments。
   - effective model 切换（含附件 turn 走 `multimodalModelId`）。
   - 错误映射为用户可读 runtime error。
6. **Provider 集成**
   - capability 判断。
   - provider-boundary materialization。
   - OpenAI-compatible image block serialize。
   - unsupported block 显式错误（含 `MULTIMODAL_MODEL_NO_VISION_CAPABILITY`）。
7. **前端 Chat**
   - upload button gate（按 `activeAgent.multimodalModelId`）。
   - upload button/chips。
   - send attachmentIds。
   - 状态和错误展示（含 `MULTIMODAL_MODEL_NOT_CONFIGURED` 跳转）。
8. **可观测和测试**
   - trace metadata（含 effective model + model_source）。
   - API/单元/前端/browser 验证。

## 测试计划

- AgentService：`multimodalModelId` CRUD round-trip；nullable 默认值；脏检查覆盖。
- AttachmentController：upload endpoint 在 `agent.multimodalModelId` 为空时返 409；非空时正常上传。
- AttachmentService：格式、magic bytes、大小限制、路径安全。
- PDF processor：文字型 PDF 抽取、空文本 PDF fallback、页数限制。
- ChatService：带附件 user message 构造正确，attachment 绑定 message；effective model 切换覆盖 3 路径（multimodal turn / runtime override turn / 主 model turn）。
- Provider：OpenAI-compatible image block serialize；unsupported image 显式错误；effective model 不支持 vision 时报 `MULTIMODAL_MODEL_NO_VISION_CAPABILITY`。
- Controller：upload、绑定、越权 session/user 防御。
- 前端 AgentDrawer：multimodal model picker 保存与回显；与 modelId 独立。
- 前端 Chat：`multimodalModelId` 未配置时附件按钮禁用 + tooltip + 跳转；配置后启用。
- 前端：上传 chip、移除、发送 attachmentIds、错误展示。
- Row-store/replay：`image_ref` / `pdf_ref` 持久化后 replay 不 shape drift，compact 不把 reference 变 base64。
- Browser：agent 配 multimodal model；新 Chat 选图片先建 session；图片 chip 出现；纯附件发送可用；unsupported `docx/xlsx/mp3` 明确提示；非 vision provider 错误可见；trace 显示 effective model 与 model_source；未配 multimodal model 时上传按钮禁用 tooltip 可见。
- 后端全量：`mvn -pl skillforge-server -am test`。
- 前端：`cd skillforge-dashboard && npm run build`，关键交互用 browser 验证。

## 风险

- **DB 膨胀**：base64 image 若写进 message JSON 会快速放大 DB。优先 reference + request-time materialize。
- **provider 兼容差异**：OpenAI-compatible 供应商的 image schema 可能不完全一致，需要 capability 配置和 trace 证据。
- **PDF 复杂性**：扫描件、加密 PDF、超大 PDF 都可能失败。MVP 必须明确错误，不做万能解析。
- **成本失控**：page image fallback 会显著增加视觉 token。Phase 1 用页数和图片尺寸硬限制。
- **安全**：上传文件是新攻击面，必须做类型/大小/path校验，不执行文档内嵌内容。
- **shape drift**：persisted `image_ref/pdf_ref` 与 provider materialized `image` 必须只在 request copy 中转换，不能污染 engine messages 或 `SessionService.updateSessionMessages` 的 prefix 比对。
- **事务补偿**：上传、append、bind、loop start 跨 DB/file/async 边界，计划必须覆盖 append 失败和 loop start 失败。
- **PDFBox 资源占用**：PDF render 必须限制页数、像素、文件大小，并避免无界并发。

## 后续拆分

- Word / Excel 输入。
- `AUDIO-INPUT-MVP`。
- OCR provider。
- precise multimodal token/cost。
- attachment TTL / cleanup / object storage。
- richer attachment preview UI。

## 评审记录

2026-05-13：用户确认图片和 PDF 一期一起做；Word/Excel 暂缓；音频输入单独列需求；GEN-UI 与多模态输入不绑定。

2026-05-13：两路方案 review 返回 `NEEDS_FIX`。已修正：新 Chat 上传生命周期、纯附件发送、`image_ref/pdf_ref` 持久化 invariant、`(session_id, seq_no)` 绑定、provider-boundary materialization、tool_result replay 兼容、trace attrs 接线、前端状态机和安全限制。

2026-05-14：用户决定 MULTIMODAL-MVP 升级到当前队列，并要求 agent 配置面独立配置多模态模型 + Chat 上传按钮按该字段 gate。已对应增加 Ratify #6-#9（PRD）+ 数据模型 `t_agent.multimodal_model_id` 字段 + effective model 选择逻辑 + AgentDrawer multimodal picker + 上传按钮 gate 与 BE 防御性 409。开工前仍需 Full pipeline 评审。
