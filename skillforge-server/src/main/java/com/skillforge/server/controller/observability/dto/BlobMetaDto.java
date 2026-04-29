package com.skillforge.server.controller.observability.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Plan §7.2 — blob 元信息（前端按 hasXxx 决定按钮可点 / disable）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BlobMetaDto(
        boolean hasRawRequest,
        boolean hasRawResponse,
        boolean hasRawSse,
        Long rawRequestSize,
        Long rawResponseSize,
        Long rawSseSize
) {}
