package com.skillforge.server.artifact;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record InteractiveArtifactManifest(
        int schemaVersion,
        String title,
        String fallback,
        List<String> permissions,
        List<String> network,
        Map<String, Object> initialData,
        Map<String, Object> stateSchema) {

    public InteractiveArtifactManifest {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        network = network == null ? List.of() : List.copyOf(network);
        initialData = immutableJsonObject(initialData, "initialData");
        stateSchema = immutableJsonObject(stateSchema, "stateSchema");
    }

    private static Map<String, Object> immutableJsonObject(
            Map<?, ?> value, String field) {
        if (value == null || value.isEmpty()) return Map.of();
        return castJsonObject(immutableJsonValue(value, field));
    }

    private static Object immutableJsonValue(Object value, String field) {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean) {
            return value;
        }
        JsonCopyFrame root = JsonCopyFrame.create(value, field);
        ArrayDeque<JsonCopyFrame> pending = new ArrayDeque<>();
        Set<Object> active = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Object, Object> completed = new IdentityHashMap<>();
        pending.push(root);
        active.add(root.source());

        while (!pending.isEmpty()) {
            JsonCopyFrame frame = pending.peek();
            if (!frame.hasNext()) {
                pending.pop();
                active.remove(frame.source());
                completed.put(frame.source(), frame.immutableView());
                continue;
            }

            JsonChild child = frame.next(field);
            Object childValue = child.value();
            if (childValue == null || childValue instanceof String
                    || childValue instanceof Number || childValue instanceof Boolean) {
                frame.add(child, childValue);
                continue;
            }
            if (!(childValue instanceof Map<?, ?>) && !(childValue instanceof List<?>)) {
                throw new IllegalArgumentException(field + " contains a non-JSON value");
            }
            if (active.contains(childValue)) {
                throw new IllegalArgumentException(field + " contains a cyclic JSON value");
            }
            if (completed.containsKey(childValue)) {
                frame.add(child, completed.get(childValue));
                continue;
            }

            JsonCopyFrame childFrame = JsonCopyFrame.create(childValue, field);
            frame.add(child, childFrame.immutableView());
            active.add(childValue);
            pending.push(childFrame);
        }
        return root.immutableView();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castJsonObject(Object value) {
        return (Map<String, Object>) value;
    }

    private record JsonChild(String key, Object value) { }

    private static final class JsonCopyFrame {
        private final Object source;
        private final Iterator<?> iterator;
        private final Map<String, Object> mapTarget;
        private final List<Object> listTarget;
        private final Object immutableView;

        private JsonCopyFrame(
                Object source,
                Iterator<?> iterator,
                Map<String, Object> mapTarget,
                List<Object> listTarget,
                Object immutableView) {
            this.source = source;
            this.iterator = iterator;
            this.mapTarget = mapTarget;
            this.listTarget = listTarget;
            this.immutableView = immutableView;
        }

        private static JsonCopyFrame create(Object value, String field) {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> target = new LinkedHashMap<>();
                return new JsonCopyFrame(
                        value, map.entrySet().iterator(), target, null,
                        Collections.unmodifiableMap(target));
            }
            if (value instanceof List<?> list) {
                List<Object> target = new ArrayList<>(list.size());
                return new JsonCopyFrame(
                        value, list.iterator(), null, target,
                        Collections.unmodifiableList(target));
            }
            throw new IllegalArgumentException(field + " contains a non-JSON value");
        }

        private boolean hasNext() {
            return iterator.hasNext();
        }

        private JsonChild next(String field) {
            Object next = iterator.next();
            if (mapTarget == null) return new JsonChild(null, next);
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) next;
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(field + " contains a non-string JSON object key");
            }
            return new JsonChild(key, entry.getValue());
        }

        private void add(JsonChild child, Object copiedValue) {
            if (mapTarget != null) {
                mapTarget.put(child.key(), copiedValue);
            } else {
                listTarget.add(copiedValue);
            }
        }

        private Object source() {
            return source;
        }

        private Object immutableView() {
            return immutableView;
        }
    }
}
