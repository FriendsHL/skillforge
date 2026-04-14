package com.skillforge.server.skill;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for session-scoped task lists.
 * Tasks persist for the duration of the server process.
 */
@Component
public class TodoStore {

    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

    public void setTasks(String sessionId, String tasksJson) {
        store.put(sessionId, tasksJson);
    }

    public String getTasks(String sessionId) {
        return store.getOrDefault(sessionId, "[]");
    }

    public void removeTasks(String sessionId) {
        store.remove(sessionId);
    }
}
