package com.skillforge.server.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class AgentNotFoundException extends RuntimeException {
    public AgentNotFoundException(Long id) {
        super("Agent not found: id=" + id);
    }
}
