package com.skillforge.server.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class SessionNotFoundException extends RuntimeException {
    public SessionNotFoundException(String id) {
        super("Session not found: " + id);
    }
}
