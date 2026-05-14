# MULTIMODAL-MVP Image + PDF 多模态输入

---
id: MULTIMODAL-MVP
mode: full
status: design-draft
priority: P1
risk: Full
created: 2026-05-13
updated: 2026-05-14
---

## 摘要

本包交付 SkillForge 的第一阶段多模态输入能力：Chat 支持上传图片和 PDF，并能把图片作为 vision content block 送给支持视觉的模型；PDF 优先抽取文本，扫描件或图片型 PDF 走 page-image vision fallback 或明确报错。原 `GAP-FILEEDIT-MULTIMODAL (4b Multimodal Read)` 不再作为独立后端补丁推进，因为没有 Chat 上传入口就无法端到端验证。

**2026-05-14 新增范围**：agent 配置面增加独立的 `multimodalModelId` 字段；Chat 上传按钮按"该 agent 是否已配置多模态模型"做 gate，未配置时按钮禁用并提示去配置。这条让上传能力与主对话模型解耦，便于把 vision/omni 模型仅在带附件 turn 启用，不影响主对话稳定性 / cache 命中。

## Backlog 对齐

- 吸收 `GAP-FILEEDIT-MULTIMODAL (4b Multimodal Read)`：修 `OpenAiProvider` image block silently dropped，并补齐图片/PDF进入模型的端到端链路。
- 保留 `GAP-FILEEDIT-MULTIMODAL (4a Read-before-Edit)` 为独立需求：它解决 stale edit 覆盖外部修改，不属于多模态输入。
- 新增并拆出 `AUDIO-INPUT-MVP`：音频需要 transcription/ASR provider，不与本包绑定。
- `GEN-UI-HTML-RENDERING` 不与本包绑定：它是输出渲染和 XSS 安全问题，本包是输入附件和模型理解问题。

## 范围裁剪

Phase 1 必须一起交付：

- Chat 上传图片和 PDF。
- session attachment 存储、回放和基础可观测。
- 图片进入 vision-capable provider。
- PDF 文本抽取；文本不足时按页转图片走 vision 或明确 fallback。
- provider/model capability 判断：不支持 vision 时不 silent drop。
- 修 OpenAI-compatible provider 的 image content block 发送路径。
- agent 配置面增加 `multimodalModelId` 字段；Chat 上传按钮按该字段是否配置 gate。

不在 Phase 1 做：

- Word / Excel。
- 音频输入。
- 实时语音、speaker diarization。
- 复杂 PDF 版式还原。
- 精准多模态 token / cost 估算。
- 通用 HTML / artifact 输出渲染。

## 阅读顺序

1. [MRD](mrd.md) - 用户痛点、阶段边界和非目标。
2. [PRD](prd.md) - 产品流程、功能需求、验收标准。
3. [技术方案](tech-design.md) - 架构、数据流、实现拆分、风险和测试计划。

## 当前状态

需求包已创建为 `design-draft`。开工前需要按 Full pipeline 做技术方案评审；实现时禁止把 Word/Excel、音频或 GEN-UI 一并塞入本包。

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 交付 | - |
