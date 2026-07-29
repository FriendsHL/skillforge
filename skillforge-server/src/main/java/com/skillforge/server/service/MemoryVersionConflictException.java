package com.skillforge.server.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Client edited a stale memory version and must refresh before retrying. */
@ResponseStatus(HttpStatus.CONFLICT)
public class MemoryVersionConflictException extends RuntimeException {

    public MemoryVersionConflictException(Long id, Long expected, Long actual) {
        super("Memory version conflict: id=" + id
                + ", expected=" + expected + ", actual=" + actual);
    }
}
