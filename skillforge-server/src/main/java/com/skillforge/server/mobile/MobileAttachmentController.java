package com.skillforge.server.mobile;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.Set;

@RestController
@RequestMapping("/api/mobile/client/sessions/{sessionId}/attachments")
public class MobileAttachmentController {

    private static final String SCOPE_CHAT_READ = "chat:read";
    private final MobileAttachmentDownloadService downloadService;

    public MobileAttachmentController(MobileAttachmentDownloadService downloadService) {
        this.downloadService = downloadService;
    }

    @GetMapping("/{attachmentId}/data")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable String sessionId,
            @PathVariable String attachmentId,
            HttpServletRequest request) {
        MobileDevicePrincipal principal = principal(request);
        if (!scopes(principal).contains(SCOPE_CHAT_READ)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        var download = downloadService.findAssistantArtifact(sessionId, attachmentId, principal.userId());
        if (download.isEmpty()) return ResponseEntity.notFound().build();

        MobileAttachmentDownloadService.Download file = download.get();
        String etag = "\"" + file.sha256() + "\"";
        HttpHeaders base;
        try {
            base = baseHeaders(file, etag);
        } catch (RuntimeException e) {
            close(file);
            throw e;
        }
        if (etagMatches(request.getHeader(HttpHeaders.IF_NONE_MATCH), etag)) {
            close(file);
            return new ResponseEntity<>(null, base, HttpStatus.NOT_MODIFIED);
        }

        String rangeHeader = request.getHeader(HttpHeaders.RANGE);
        boolean useRange = rangeHeader != null && ifRangeMatches(request.getHeader(HttpHeaders.IF_RANGE), etag);
        if (!useRange) return full(file, base);
        ByteRange range = parseRange(rangeHeader, file.size());
        if (range == null) {
            close(file);
            base.set(HttpHeaders.CONTENT_RANGE, "bytes */" + file.size());
            return new ResponseEntity<>(null, base, HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        }
        base.set(HttpHeaders.CONTENT_RANGE,
                "bytes " + range.start() + "-" + range.end() + "/" + file.size());
        base.setContentLength(range.length());
        return new ResponseEntity<>(stream(file, range.start(), range.length()), base, HttpStatus.PARTIAL_CONTENT);
    }

    private static ResponseEntity<StreamingResponseBody> full(
            MobileAttachmentDownloadService.Download file, HttpHeaders headers) {
        headers.setContentLength(file.size());
        return new ResponseEntity<>(stream(file, 0, file.size()), headers, HttpStatus.OK);
    }

    private static HttpHeaders baseHeaders(MobileAttachmentDownloadService.Download file, String etag) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.setETag(etag);
        headers.setContentType(MediaType.parseMediaType(file.mimeType()));
        headers.setContentDisposition((file.inline() ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(file.filename(), StandardCharsets.UTF_8).build());
        headers.setCacheControl(CacheControl.noCache().cachePrivate());
        headers.set("X-Content-Type-Options", "nosniff");
        return headers;
    }

    private static StreamingResponseBody stream(
            MobileAttachmentDownloadService.Download file, long start, long length) {
        return output -> {
            try (file) {
                file.writeTo(output, start, length);
            }
        };
    }

    static ByteRange parseRange(String value, long size) {
        if (value == null || !value.startsWith("bytes=") || value.indexOf(',') >= 0 || size <= 0) return null;
        String spec = value.substring(6).trim();
        int dash = spec.indexOf('-');
        if (dash < 0 || spec.indexOf('-', dash + 1) >= 0) return null;
        try {
            if (dash == 0) {
                long suffix = Long.parseLong(spec.substring(1));
                if (suffix <= 0) return null;
                long length = Math.min(suffix, size);
                return new ByteRange(size - length, size - 1);
            }
            long start = Long.parseLong(spec.substring(0, dash));
            long end = dash == spec.length() - 1 ? size - 1 : Long.parseLong(spec.substring(dash + 1));
            if (start < 0 || start >= size || end < start) return null;
            return new ByteRange(start, Math.min(end, size - 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean etagMatches(String header, String etag) {
        if (header == null) return false;
        for (String candidate : header.split(",")) {
            String normalized = candidate.trim();
            if ("*".equals(normalized) || etag.equals(normalized)
                    || (normalized.startsWith("W/") && etag.equals(normalized.substring(2)))) return true;
        }
        return false;
    }

    private static boolean ifRangeMatches(String header, String etag) {
        return header == null || (!header.trim().startsWith("W/") && etag.equals(header.trim()));
    }

    private static void close(MobileAttachmentDownloadService.Download file) {
        try {
            file.close();
        } catch (java.io.IOException ignored) {
            // No response body will consume the handle on metadata-only responses.
        }
    }

    private static MobileDevicePrincipal principal(HttpServletRequest request) {
        Object value = request.getAttribute(MobileAuthInterceptor.PRINCIPAL_ATTRIBUTE);
        if (value instanceof MobileDevicePrincipal principal) return principal;
        throw new IllegalStateException("Mobile principal missing after authentication");
    }

    private static Set<String> scopes(MobileDevicePrincipal principal) {
        return principal.scopes() != null ? principal.scopes() : Set.of();
    }

    record ByteRange(long start, long end) {
        long length() { return end - start + 1; }
    }
}
