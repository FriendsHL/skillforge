package com.skillforge.server.controller;

import com.skillforge.server.config.AuthInterceptor;
import com.skillforge.server.service.AuthService;
import com.skillforge.server.service.WorkspaceFileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkspaceControllerTest {

    private WorkspaceFileService workspaceFileService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        workspaceFileService = mock(WorkspaceFileService.class);
        AuthService authService = mock(AuthService.class);
        when(authService.isValidToken("valid-token")).thenReturn(true);
        mvc = MockMvcBuilders.standaloneSetup(new WorkspaceController(workspaceFileService))
                .addInterceptors(new AuthInterceptor(authService))
                .build();
    }

    @Test
    void entries_missingAuthorization_isRejectedBeforeFileAccess() throws Exception {
        mvc.perform(get("/api/workspace/entries"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(workspaceFileService);
    }

    @Test
    void entries_authenticated_returnsRelativeContractWithoutAbsolutePaths() throws Exception {
        when(workspaceFileService.list("docs")).thenReturn(new WorkspaceFileService.DirectoryListing(
                "skillforge", "docs", "", List.of(new WorkspaceFileService.Entry(
                "README.md", "docs/README.md", WorkspaceFileService.EntryType.FILE,
                12L, Instant.parse("2026-07-14T01:00:00Z"), true)), false));

        mvc.perform(get("/api/workspace/entries")
                        .header("Authorization", "Bearer valid-token")
                        .param("path", "docs"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.rootLabel").value("skillforge"))
                .andExpect(jsonPath("$.path").value("docs"))
                .andExpect(jsonPath("$.parentPath").value(""))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.entries[0].path").value("docs/README.md"))
                .andExpect(jsonPath("$.entries[0].type").value("file"))
                .andExpect(jsonPath("$..absolutePath").doesNotExist());
    }

    @Test
    void content_authenticated_returnsBoundedPreviewContract() throws Exception {
        when(workspaceFileService.content("README.md")).thenReturn(new WorkspaceFileService.FileContent(
                "README.md", "README.md", 120L, Instant.parse("2026-07-14T01:00:00Z"),
                "hello", true, false));

        mvc.perform(get("/api/workspace/content")
                        .header("Authorization", "Bearer valid-token")
                        .param("path", "README.md"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.path").value("README.md"))
                .andExpect(jsonPath("$.content").value("hello"))
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.binary").value(false))
                .andExpect(jsonPath("$..absolutePath").doesNotExist());
    }

    @Test
    void errors_areMappedWithoutLeakingInternalMessages() throws Exception {
        when(workspaceFileService.list(anyString()))
                .thenThrow(new WorkspaceFileService.WorkspaceFileException(
                        WorkspaceFileService.ErrorKind.INVALID_PATH, "/private/root must not leak"));

        mvc.perform(get("/api/workspace/entries")
                        .header("Authorization", "Bearer valid-token")
                        .param("path", "../secret"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error").value("invalid_path"))
                .andExpect(jsonPath("$.message").value("The workspace path is invalid."));
    }

    @Test
    void unavailableMissingAndIoErrors_haveStableStatusesAndBodies() throws Exception {
        when(workspaceFileService.list("unavailable"))
                .thenThrow(new WorkspaceFileService.WorkspaceFileException(
                        WorkspaceFileService.ErrorKind.UNAVAILABLE, "/private/root"));
        when(workspaceFileService.list("missing"))
                .thenThrow(new WorkspaceFileService.WorkspaceFileException(
                        WorkspaceFileService.ErrorKind.NOT_FOUND, "/private/root/missing"));
        when(workspaceFileService.list("io"))
                .thenThrow(new WorkspaceFileService.WorkspaceFileException(
                        WorkspaceFileService.ErrorKind.IO_ERROR, "/private/root/io"));

        mvc.perform(get("/api/workspace/entries")
                        .header("Authorization", "Bearer valid-token").param("path", "unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("workspace_unavailable"));
        mvc.perform(get("/api/workspace/entries")
                        .header("Authorization", "Bearer valid-token").param("path", "missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mvc.perform(get("/api/workspace/entries")
                        .header("Authorization", "Bearer valid-token").param("path", "io"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("workspace_io_error"));
    }
}
