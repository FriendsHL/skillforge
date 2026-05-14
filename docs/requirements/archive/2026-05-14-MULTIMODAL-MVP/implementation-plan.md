# MULTIMODAL-MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Phase 1 image/PDF attachment path for SkillForge Chat, preserving `image_ref` / `pdf_ref` in session history and materializing provider image bytes only for outbound LLM requests. **Agents gain an independent `multimodalModelId` config; Chat upload button is gated by that field; the multimodal turn switches effective model.**

**Architecture:** Add a server-side attachment model, upload API, and local storage service. Chat persists attachment reference blocks on the user message, binds attachments to `(session_id, seq_no)`, and materializes those references after effective-model resolution (priority: `agent.multimodalModelId` when attachments present > `/model` runtime override > `agent.modelId`). Provider serialization is updated so regular user multimodal messages keep image blocks, while tool-result replay keeps its defensive filtering behavior.

**Tech Stack:** Java 17, Spring Boot 3.2, JPA/Hibernate, Flyway, PDFBox, React 19, TypeScript, Ant Design 6, Vite.

---

## File Map

- `skillforge-server/src/main/resources/db/migration/V71__add_agent_multimodal_model_id.sql`: add `t_agent.multimodal_model_id`.
- `skillforge-server/src/main/java/com/skillforge/server/entity/AgentEntity.java`: add `multimodalModelId` field/getter/setter.
- `skillforge-server/src/main/java/com/skillforge/server/dto/AgentDTO.java` (or equivalent request/response): carry `multimodalModelId`.
- `skillforge-server/src/main/java/com/skillforge/server/controller/AgentController.java` / `AgentService.java`: persist and return the new field.
- `skillforge-dashboard/src/components/agents/AgentDrawer.tsx`: multimodal model picker, dirty check, save payload.
- `skillforge-server/src/main/resources/db/migration/V70__chat_attachments.sql`: create `t_chat_attachment` (already in working tree).
- `skillforge-server/src/main/java/com/skillforge/server/entity/SessionAttachmentEntity.java`: JPA entity.
- `skillforge-server/src/main/java/com/skillforge/server/repository/SessionAttachmentRepository.java`: lookup unbound/bound attachments.
- `skillforge-server/src/main/java/com/skillforge/server/config/AttachmentProperties.java`: storage limits/root.
- `skillforge-server/src/main/java/com/skillforge/server/service/AttachmentService.java`: validate upload, store file, bind to message seq, load materialization inputs.
- `skillforge-server/src/main/java/com/skillforge/server/service/AttachmentRequestMaterializer.java`: turn `image_ref` / `pdf_ref` into request-time text/image blocks.
- `skillforge-server/src/main/java/com/skillforge/server/controller/ChatAttachmentController.java`: upload/list endpoints.
- `skillforge-server/src/main/java/com/skillforge/server/dto/ChatRequest.java`: add `attachmentIds`.
- `skillforge-server/src/main/java/com/skillforge/server/controller/ChatController.java`: pass `attachmentIds`.
- `skillforge-server/src/main/java/com/skillforge/server/service/ChatService.java`: new overload, persisted reference blocks, binding, materialization after runtime model override.
- `skillforge-core/src/main/java/com/skillforge/core/model/ContentBlock.java`: add attachment/image fields and factories.
- `skillforge-core/src/main/java/com/skillforge/core/model/Message.java`: include safe attachment placeholder in `getTextContent`.
- `skillforge-core/src/main/java/com/skillforge/core/llm/OpenAiProvider.java`: serialize regular user image blocks.
- `skillforge-server/src/main/java/com/skillforge/server/config/LlmProperties.java`: model-level `supportsImageInput`.
- `skillforge-dashboard/src/api/index.ts`: upload/list APIs and `sendMessage` payload.
- `skillforge-dashboard/src/components/ChatWindow.tsx`: attachment picker/chips and attachment-only send.
- `skillforge-dashboard/src/pages/Chat.tsx`: create draft session on first attachment, upload lifecycle, pass attachmentIds.

## Tasks

### Task 0: Agent multimodal model config

- [ ] Write failing tests: AgentService CRUD round-trip with `multimodalModelId`; AgentController POST/PATCH accepts and returns the field.
- [ ] Add V71 migration: `ALTER TABLE t_agent ADD COLUMN multimodal_model_id VARCHAR(64);`.
- [ ] Add `multimodalModelId` to `AgentEntity` + DTO + request body + service mapping.
- [ ] Update AgentDrawer with `Multimodal model` picker (`useLlmModels` options, optional), dirty check, save payload.
- [ ] Run targeted tests to GREEN.

### Task 1: ContentBlock and OpenAI Serialization

- [ ] Write failing core tests for `ContentBlock.imageRef`, `ContentBlock.image`, and OpenAI regular user image serialization.
- [ ] Run targeted test and verify RED: `mvn -pl skillforge-core -Dtest=OpenAiProviderConvertMessagesTest,MessageGetTextContentTest test`.
- [ ] Add fields/factories to `ContentBlock` and safe placeholders in `Message.getTextContent`.
- [ ] Update `OpenAiProvider` regular user block-list serialization to emit OpenAI content arrays for text + image blocks.
- [ ] Preserve existing tool-result replay filtering test.
- [ ] Run targeted tests to GREEN.

### Task 2: Attachment Persistence and Upload API

- [ ] Write failing server tests for upload validation, ownership, unbound attachment creation, and `409 MULTIMODAL_MODEL_NOT_CONFIGURED` when `agent.multimodalModelId` is null.
- [ ] Reconcile working-tree `V70__chat_attachments.sql` with entity/repository/properties/service/controller, and add PDFBox dependency.
- [ ] Implement local storage with generated relative paths, MIME/magic checks, size limits, and filename sanitization.
- [ ] Add upload-endpoint precondition: load session → load agent → reject if `multimodalModelId` is blank.
- [ ] Run targeted server tests to GREEN.

### Task 3: Chat Send Binding and Reference Blocks

- [ ] Write failing `ChatService` tests for `attachmentIds` producing persisted `image_ref` blocks and binding to message seq.
- [ ] Extend `ChatRequest`, `ChatController`, and `ChatService.chatAsync` overloads.
- [ ] Keep existing text-only and queued-message behavior unchanged.
- [ ] Run targeted ChatService/Controller tests to GREEN.

### Task 4: Request-Time Materialization

- [ ] Write failing tests proving:
  - Effective-model resolution priority: attachment present + `agent.multimodalModelId` set → that model is used; else `/model` runtime override; else `agent.modelId`.
  - Non-vision multimodal model rejects image refs with `MULTIMODAL_MODEL_NO_VISION_CAPABILITY` (no silent fallback to `modelId`).
- [ ] Add `AttachmentRequestMaterializer` with image compression and PDF text/page-image logic.
- [ ] Add model-level `supportsImageInput` config and default `mimo-v2-omni` support in YAML.
- [ ] Materialize a copy of history/user message before `AgentLoopEngine.run`, never mutating persisted messages.
- [ ] Emit trace span attrs `llm.effective_model` and `llm.model_source` for observability.
- [ ] Run targeted tests to GREEN.

### Task 5: Frontend Attachment UX

- [ ] Write failing React tests:
  - Attachment-only send and upload chip lifecycle.
  - Attachment button is disabled when `activeAgent.multimodalModelId` is falsy; tooltip text + link visible.
  - Attachment button enables when `multimodalModelId` is non-empty.
  - Upload `409 MULTIMODAL_MODEL_NOT_CONFIGURED` response surfaces the same tooltip + jump link.
- [ ] Add upload APIs.
- [ ] Update `ChatWindow` send contract to `(text, attachmentIds)` and enable send for attachments.
- [ ] Add upload-button gate based on `activeAgent.multimodalModelId`.
- [ ] Update `Chat.tsx` to create a draft session on first attachment and manage attachment states.
- [ ] Run frontend tests/build to GREEN.

### Task 6: Verification

- [ ] Run backend targeted tests.
- [ ] Run `mvn -pl skillforge-server -am test`.
- [ ] Run `cd skillforge-dashboard && npm run build`.
- [ ] Start server and dashboard if needed.
- [ ] Browser-check:
  - AgentDrawer multimodal model picker saves and reloads.
  - Without `multimodalModelId` configured: attachment button is disabled, tooltip and jump link visible.
  - With `multimodalModelId` configured: upload image chip, attachment-only send work.
  - Unsupported `docx/xlsx/mp3` clearly rejected.
  - Visible non-vision error when `multimodalModelId` points to a non-vision model.
  - Trace shows `llm.effective_model` and `llm.model_source` for the multimodal turn vs. a follow-up text-only turn.

