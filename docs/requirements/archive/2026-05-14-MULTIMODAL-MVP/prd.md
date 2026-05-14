# MULTIMODAL-MVP PRD

---
id: MULTIMODAL-MVP
status: design-draft
owner: youren
priority: P1
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-05-13
updated: 2026-05-14
---

## 摘要

交付图片和 PDF 的端到端多模态输入能力。用户在 Chat 输入框上传图片或 PDF，系统保存为 session attachment，按文件类型转换为模型可消费的 content blocks，再随当前 user message 一起进入 agent loop。provider 不支持所需能力时必须明确失败或降级，禁止 silent drop。

## 已 Ratify 决策

1. **图片和 PDF 一期一起做。** 单做后端 FileRead/provider 修复不可测试，也不能形成产品能力。
2. **Word / Excel 暂缓。** 它们需要独立的文档解析和表格压缩策略，不阻塞图片/PDF闭环。
3. **音频独立成包。** 音频输入依赖 ASR/transcription provider，不属于 vision/PDF链路。
4. **GEN-UI 不绑定。** GEN-UI 是输出渲染、安全和 HTML/artifact 问题，本包是输入附件和模型理解问题。
5. **一期不追求精准 token/cost。** 先做保守文件大小、页数、图片压缩限制和 trace 标记。
6. **agent 独立配置多模态模型 + 上传 gate。** Agent 配置面新增独立的 `multimodalModelId` 字段（nullable，与现有 `modelId` 并列）；Chat 上传按钮的可点击状态严格 gate 在该字段是否非空。未配置时按钮**禁用 + tooltip 提示"请先在 agent 配置中选择多模态模型"**（不隐藏，让用户能发现入口）。
7. **多模态 turn 切换模型。** 当本次 user message 携带 image_ref / pdf_ref 时，该 turn 的 LLM 调用使用 `multimodalModelId` 而非主 `modelId`；不带附件的 turn 继续走主 `modelId`。后续 turn 是否继续用多模态模型由 ChatService 按"当前 turn 是否仍含未消费 vision block"决定（不影响 follow-up 纯文本 turn 的 cache 命中）。
8. **多模态模型可与主模型相同。** 若用户已经使用的主模型本身就是 vision-capable（如 gpt-4o / mimo-v2-omni / claude-sonnet-4），允许把 `multimodalModelId` 配成同一个 model；FE 不强制阻止，BE 直接生效。
9. **上传 gate 仅看 agent 配置，不实时探测 provider capability。** FE 不发探测请求，只读 `agent.multimodalModelId` 是否非空；BE 在 provider materializer 处仍做 capability 严格校验，配置了不支持 vision 的 model 时报明确错误而不是 silent drop（落到 Ratify #6/7 的明确失败路径）。

## 用户流程

1. 用户打开 Chat session。
2. 用户**当前 agent 必须已配置 `multimodalModelId`**，否则附件按钮显示禁用状态 + tooltip 提示去 agent 配置面设置；点击 tooltip 中的链接可直接跳到 agent 配置。
3. 用户点击输入区附件按钮，选择图片或 PDF。
4. 如果当前还没有 session，前端先创建一个 draft session，再上传附件到该 session。
5. 前端展示待发送附件 chip，用户可移除。
6. 用户输入可选文字说明并发送；只有附件、没有文字也允许发送。
7. 后端保存附件，创建 user message：
   - 图片：文本说明 + image content block。
   - PDF：文本说明 + PDF 抽取文本；必要时附 page image content block 或明确 fallback。
8. Agent 正常运行，**该 turn 的 LLM 调用使用 `agent.multimodalModelId`**；模型可以使用附件内容回答。
9. Chat / Session detail 显示附件名称、类型、处理状态和错误提示。

## 支持格式

Phase 1 支持：

- 图片：`png`、`jpg`、`jpeg`、`webp`。
- PDF：`pdf`。

Phase 1 不支持：

- Word：`doc`、`docx`。
- Excel：`xls`、`xlsx`、`csv`。
- 音频：`mp3`、`wav`、`m4a` 等。
- 视频。

## 功能需求

### Agent 配置 multimodal model

- Agent 配置面（AgentDrawer / AgentEditor）在主 `Model` 选择器旁新增独立的 `Multimodal model`（可选）选择器。
- 选择器数据源复用 `useLlmModels`，与主 model picker 同一个 model 列表（不做二次过滤；用户可自行选择任意 model）。
- `multimodalModelId` 字段 nullable，未配置时不影响主对话；配置后仅在 Chat 出现附件 turn 时生效。
- 删除主 `modelId` 不联动删 `multimodalModelId`（两者独立）；切换主 `modelId` 不联动改 `multimodalModelId`。
- AgentDrawer 必须把 `multimodalModelId` 的脏检查纳入 "未保存修改" 提示，避免用户切走丢配置。
- API：`Agent` 创建/更新接口的 request body 增加可选 `multimodalModelId: string | null` 字段；返回体相应携带。
- 持久化：`t_agent` 表加 `multimodal_model_id VARCHAR(64)` nullable 列。

### Chat 上传

- Chat 输入区必须有附件入口。
- **附件入口按 `agent.multimodalModelId` 是否非空 gate**：
  - 配置存在 → 按钮可点击。
  - 配置缺失 → 按钮**禁用**（灰态、不允许点击 / 不打开文件选择器），hover 显示 tooltip “请先在 agent 配置中选择多模态模型”，tooltip 内带跳转链接到当前 agent 的配置面。
  - 不采用”隐藏按钮”，避免用户不知道功能存在。
- 发送前必须展示附件 chip，包含文件名、类型、大小和移除操作。
- 同一条消息可带文字和附件。
- 纯附件消息合法；发送按钮在 `trimmedText.length > 0 || attachmentIds.length > 0` 时可用。
- 新建 Chat 选择附件时必须先创建 session，再上传附件；不采用”无 session 的临时上传”。
- 上传失败、格式不支持、文件过大时必须在 UI 明确提示。
- 发送后附件必须绑定到该 session 和 user message。

### Attachment 存储

- 后端必须保存原始文件或可追溯引用。
- attachment 必须记录 sessionId、绑定 message 的 `(session_id, seq_no)`、文件名、MIME type、大小、处理状态、处理错误。
- 文件名仅用于展示，不可作为磁盘可信路径。
- 需要基础大小限制和允许列表。

### 图片处理

- 图片必须校验 MIME / magic bytes，不能只信前端扩展名。
- 图片必须按 provider 限制做尺寸或字节数压缩。
- 图片进入支持 vision 的 provider 时必须作为 image content block，而不是转成纯文本占位符。
- 如果 provider/model 不支持 vision，必须明确提示切换模型或移除图片。
- 持久化的 session message 不写入图片 base64；只写 `image_ref` / `pdf_ref` 等 attachment reference。

### PDF 处理

- 文字型 PDF 优先抽取文本并作为 text content block 注入。
- 抽取文本必须有最大字符数 / 页数限制，超限时明确截断提示。
- 扫描件或文本不足的 PDF：
  - 若当前 provider 支持 vision，可把有限页数转 page image content blocks。
  - 若不支持 vision，返回明确错误或提示用户切换模型。
- PDF 处理失败不得导致附件静默消失。

### Provider capability

- 系统必须能判断当前 provider/model 是否支持 image input。
- 当 user message 含 image_ref / pdf_ref 时，**effective model = `agent.multimodalModelId`**（不是主 `agent.modelId`，也不是 `/model` runtime override —— Ratify #7）。
- 若 `agent.multimodalModelId` 配置的 model 实际不支持 vision，BE 必须显式报错（错误码 `MULTIMODAL_MODEL_NO_VISION_CAPABILITY`），不 silent drop image block，也不 fallback 到主 model。
- OpenAI-compatible provider 必须修复 image block silently dropped。
- 不支持的 content block 类型必须显式报错或走明确降级策略。
- trace 中应记录 provider capability 判断结果、effective model 和最终发送的 block 类型。

### 可观测

- Session/trace 排查时能看到附件处理状态。
- LLM payload 或 span metadata 能体现图片/PDF是否进入模型请求。
- 失败原因必须对用户和运维可区分：格式不支持、文件过大、PDF抽取失败、provider不支持vision、provider请求失败。

## 非目标

- 不做 Word / Excel。
- 不做音频。
- 不做 OCR 供应商集成；扫描 PDF 的一期 fallback 优先依赖 vision model。
- 不做完整对象存储抽象。
- 不做跨用户权限模型升级。
- 不做 HTML/artifact 输出渲染。
- 不做 precise token/cost。
- 不做移动端专项 UI。

## 验收标准

- [ ] Agent 配置面有独立的 `Multimodal model` 选择器，能保存并回显。
- [ ] `agent.multimodalModelId` 未配置时，Chat 附件按钮禁用 + tooltip 提示去配置；点击 tooltip 链接跳到 agent 配置面。
- [ ] `agent.multimodalModelId` 配置后，Chat 附件按钮可点击，能正常打开文件选择器并上传。
- [ ] 携带附件的 user message turn，BE 实际调用 LLM 使用 `agent.multimodalModelId`；不带附件的 turn 仍使用主 `agent.modelId`（可由 trace 验证）。
- [ ] `agent.multimodalModelId` 配置的 model 不支持 vision 时，发送带图消息明确报错 `MULTIMODAL_MODEL_NO_VISION_CAPABILITY`，不 silent drop。
- [ ] Chat 页面能上传并发送图片。
- [ ] 新建 Chat 中选择附件会先创建 session，并能继续发送附件消息。
- [ ] 不输入文字、只上传附件也能发送。
- [ ] vision-capable provider 收到真实 image content block，模型回答能引用图片内容。
- [ ] OpenAI-compatible provider 不再 silently drop image blocks。
- [ ] Chat 页面能上传并发送文字型 PDF。
- [ ] PDF 文本被抽取并进入当前 user message，agent 能基于 PDF 内容回答。
- [ ] 扫描 PDF 在 vision-capable provider 下走 page image fallback，或在不支持时给出明确错误。
- [ ] 非 vision provider 收到图片时明确拒绝或提示切换模型，不 silent drop。
- [ ] 附件大小、页数、格式限制有后端校验和前端提示。
- [ ] session/trace 可看到附件处理路径、effective model 和失败原因。
- [ ] Word / Excel / audio 上传时明确不支持，不误走图片/PDF路径。

## 后续 Backlog

- `AUDIO-INPUT-MVP`：音频上传、ASR/transcription、transcript 注入 chat。
- Word / Excel 文档输入：POI / 表格压缩 / markdown table。
- OCR provider：对扫描 PDF 做更稳定的文本抽取。
- precise multimodal token/cost。
- 生产级 attachment storage：对象存储、TTL、清理任务、访问控制。
