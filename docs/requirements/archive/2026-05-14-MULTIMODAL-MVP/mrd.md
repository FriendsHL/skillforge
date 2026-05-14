# MULTIMODAL-MVP MRD

---
id: MULTIMODAL-MVP
status: design-draft
owner: youren
priority: P1
risk: Full
created: 2026-05-13
updated: 2026-05-13
---

## 背景

SkillForge 目前的 Chat 主流程仍是纯文本输入。即便用户选择了 `mimo-v2-omni`、GPT-4o 或 Claude Vision 这类可理解图片的模型，系统也没有端到端路径让用户上传图片或 PDF，并把内容送到模型。

现有 backlog 里曾有 `GAP-FILEEDIT-MULTIMODAL (4b Multimodal Read)`，它偏后端：让 FileRead 识别 image/PDF，并修 provider 发送 image block。但用户确认：如果没有 Chat 上传入口，这个后端-only 修复不可测试，也不能构成产品能力。因此本包把 4b 吸收进完整的一期多模态输入 MVP。

## 用户问题

用户希望把截图、图片资料、PDF 文档直接给 agent 看：

- 上传截图后，让 agent 根据图中 UI、报错、表格或设计内容继续工作。
- 上传 PDF 后，让 agent 阅读文字型 PDF；遇到扫描件时至少能明确 fallback，而不是静默失败。
- 使用 omni/vision 模型时，系统不能把 image block silently dropped。
- 管理端 / dashboard 需要能看到附件进入了哪个 session、采用了什么解析路径、失败原因是什么。

## 用户价值

- 用户不用先手工 OCR 或截图转文本。
- vision 模型能力能在 SkillForge Chat 中被真实使用和验证。
- PDF 文档分析从“完全没入口”变成可用的最小闭环。
- provider 不支持 vision 时给出明确行为，减少“模型明明是 omni 但看不到图”的排查成本。

## 本包目标

本包只做 Phase 1：图片 + PDF 多模态输入。

需要做到：

- Chat 页面可以上传图片和 PDF。
- 上传文件绑定到当前 session / user message，可回放、可追踪。
- 图片作为多模态 content block 发给支持 vision 的 provider。
- PDF 优先抽取文本；文本不足或扫描件按设计走 page image vision fallback 或明确错误。
- provider/model 能力不足时不 silent drop，并给出可行动提示。
- OpenAI-compatible provider 修复 image block 发送路径。

## 不在本包做

- Word / Excel 解析。
- 音频上传和转写。
- 实时语音输入。
- speaker diarization。
- 高保真 PDF 版式还原。
- 可编辑 artifact / HTML 输出渲染。
- 精确多模态 token 和成本核算。
- 外部对象存储、S3、多租户附件隔离的完整生产级方案。

## 成功信号

- 用户能在 Chat 上传一张图片，vision 模型能基于图片内容作答。
- 用户能上传文字型 PDF，agent 能基于 PDF 文本作答。
- 用户上传扫描 PDF 时，系统能走明确 fallback 或给出清晰失败原因。
- 使用不支持 vision 的 provider 时，图片不会静默丢弃。
- trace / session detail 能帮助排查附件处理路径。
