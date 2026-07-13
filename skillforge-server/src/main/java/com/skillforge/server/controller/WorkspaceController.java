package com.skillforge.server.controller;

import com.skillforge.server.dto.WorkspaceContentResponse;
import com.skillforge.server.dto.WorkspaceEntriesResponse;
import com.skillforge.server.dto.WorkspaceEntryResponse;
import com.skillforge.server.dto.WorkspaceErrorResponse;
import com.skillforge.server.service.WorkspaceFileService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@RestController
@RequestMapping(path = "/api/workspace", produces = MediaType.APPLICATION_JSON_VALUE)
public class WorkspaceController {

    private final WorkspaceFileService workspaceFileService;

    public WorkspaceController(WorkspaceFileService workspaceFileService) {
        this.workspaceFileService = workspaceFileService;
    }

    @GetMapping("/entries")
    public ResponseEntity<WorkspaceEntriesResponse> entries(@RequestParam(defaultValue = "") String path) {
        WorkspaceFileService.DirectoryListing listing = workspaceFileService.list(path);
        WorkspaceEntriesResponse response = new WorkspaceEntriesResponse(
                listing.rootLabel(),
                listing.path(),
                listing.parentPath(),
                listing.entries().stream().map(entry -> new WorkspaceEntryResponse(
                        entry.name(),
                        entry.path(),
                        entry.type().name().toLowerCase(Locale.ROOT),
                        entry.sizeBytes(),
                        entry.modifiedAt(),
                        entry.previewable())).toList(),
                listing.truncated());
        return noStore(HttpStatus.OK, response);
    }

    @GetMapping("/content")
    public ResponseEntity<WorkspaceContentResponse> content(@RequestParam(defaultValue = "") String path) {
        WorkspaceFileService.FileContent content = workspaceFileService.content(path);
        WorkspaceContentResponse response = new WorkspaceContentResponse(
                content.name(),
                content.path(),
                content.sizeBytes(),
                content.modifiedAt(),
                content.content(),
                content.truncated(),
                content.binary());
        return noStore(HttpStatus.OK, response);
    }

    @ExceptionHandler(WorkspaceFileService.WorkspaceFileException.class)
    public ResponseEntity<WorkspaceErrorResponse> handleWorkspaceError(
            WorkspaceFileService.WorkspaceFileException exception) {
        return switch (exception.kind()) {
            case INVALID_PATH -> error(HttpStatus.BAD_REQUEST, "invalid_path",
                    "The workspace path is invalid.");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "not_found",
                    "The workspace entry was not found.");
            case UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE, "workspace_unavailable",
                    "The workspace is unavailable.");
            case IO_ERROR -> error(HttpStatus.INTERNAL_SERVER_ERROR, "workspace_io_error",
                    "The workspace could not be read.");
        };
    }

    private ResponseEntity<WorkspaceErrorResponse> error(HttpStatus status, String code, String message) {
        return noStore(status, new WorkspaceErrorResponse(code, message));
    }

    private <T> ResponseEntity<T> noStore(HttpStatus status, T body) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
