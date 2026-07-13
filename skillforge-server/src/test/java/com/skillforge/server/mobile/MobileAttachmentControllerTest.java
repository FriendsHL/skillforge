package com.skillforge.server.mobile;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class MobileAttachmentControllerTest {

    @Mock MobileAttachmentDownloadService downloadService;
    @Mock HttpServletRequest request;
    Path file;
    MobileAttachmentController controller;
    MobileAttachmentDownloadService.Download download;

    @BeforeEach
    void setUp() throws Exception {
        file = Files.createTempFile("mobile-artifact", ".pdf");
        Files.writeString(file, "0123456789");
        download = new MobileAttachmentDownloadService.Download(
                file, 10, "application/pdf", "report.pdf", "a".repeat(64), true);
        controller = new MobileAttachmentController(downloadService);
        when(request.getAttribute(MobileAuthInterceptor.PRINCIPAL_ATTRIBUTE)).thenReturn(
                new MobileDevicePrincipal(UUID.randomUUID(), 7L, "iPhone", Set.of("chat:read")));
        when(downloadService.findAssistantArtifact("session-1", "attachment-1", 7L))
                .thenReturn(Optional.of(download));
    }

    @Test
    void streamsFullResponseWithSecureHeaders() throws Exception {
        var response = controller.download("session-1", "attachment-1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(response.getHeaders().getETag()).isEqualTo("\"" + "a".repeat(64) + "\"");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);
        assertThat(output.toString()).isEqualTo("0123456789");
    }

    @Test
    void supportsSingleRangeAndIfRangeFallback() throws Exception {
        lenient().when(request.getHeader(HttpHeaders.RANGE)).thenReturn("bytes=2-5");
        var partial = controller.download("session-1", "attachment-1", request);
        assertThat(partial.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(partial.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 2-5/10");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        partial.getBody().writeTo(output);
        assertThat(output.toString()).isEqualTo("2345");

        lenient().when(request.getHeader(HttpHeaders.IF_RANGE)).thenReturn("\"different\"");
        var full = controller.download("session-1", "attachment-1", request);
        assertThat(full.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(full.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isNull();
    }

    @Test
    void weakIfRangeNeverAuthorizesPartialResponse() {
        lenient().when(request.getHeader(HttpHeaders.RANGE)).thenReturn("bytes=2-5");
        lenient().when(request.getHeader(HttpHeaders.IF_RANGE))
                .thenReturn("W/\"" + "a".repeat(64) + "\"");

        var response = controller.download("session-1", "attachment-1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isNull();
    }

    @Test
    void handlesNotModifiedAndRejectsMultipleOrInvalidRanges() {
        lenient().when(request.getHeader(HttpHeaders.IF_NONE_MATCH)).thenReturn("W/\"" + "a".repeat(64) + "\"");
        assertThat(controller.download("session-1", "attachment-1", request).getStatusCode())
                .isEqualTo(HttpStatus.NOT_MODIFIED);

        lenient().when(request.getHeader(HttpHeaders.IF_NONE_MATCH)).thenReturn(null);
        lenient().when(request.getHeader(HttpHeaders.RANGE)).thenReturn("bytes=0-1,4-5");
        var multiple = controller.download("session-1", "attachment-1", request);
        assertThat(multiple.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        assertThat(multiple.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */10");

        lenient().when(request.getHeader(HttpHeaders.RANGE)).thenReturn("bytes=99-");
        assertThat(controller.download("session-1", "attachment-1", request).getStatusCode())
                .isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
    }
}
