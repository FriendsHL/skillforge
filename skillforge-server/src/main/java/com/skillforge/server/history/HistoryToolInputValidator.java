package com.skillforge.server.history;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Execution-side closed validator; JSON Schema is never treated as the security boundary. */
@Component
public class HistoryToolInputValidator {

    private static final List<String> SEARCH_FIELDS = List.of(
            "query", "seqFrom", "seqTo", "roles", "kinds", "toolName", "toolUseId",
            "compacted", "summaryState", "limit", "cursor");
    private static final Set<String> SEARCH_FIELD_SET = Set.copyOf(SEARCH_FIELDS);
    private static final List<String> READ_FIELDS = List.of(
            "refs", "seqFrom", "seqTo", "aroundSeq", "before", "after", "tail",
            "archiveRef", "offset", "maxChars", "cursor");
    private static final Set<String> READ_FIELD_SET = Set.copyOf(READ_FIELDS);
    private static final Set<String> ROLES = Set.of("USER", "ASSISTANT");
    private static final Set<String> KINDS = Set.of("TEXT", "TOOL_USE", "TOOL_RESULT", "SUMMARY");
    private static final Set<String> COMPACTED = Set.of("ANY", "COMPACTED", "UNCOMPACTED");
    private static final Set<String> SUMMARY_STATES = Set.of("ANY", "ACTIVE", "SUPERSEDED");

    public SessionHistorySearchInput validateSearchInput(Map<String, Object> rawInput) {
        Map<String, Object> input = validateSearch(rawInput);
        return new SessionHistorySearchInput(
                (String) input.get("query"),
                (Long) input.get("seqFrom"),
                (Long) input.get("seqTo"),
                enumList(input, "roles", SessionHistorySearchInput.Role.class),
                enumList(input, "kinds", SessionHistorySearchInput.Kind.class),
                (String) input.get("toolName"),
                (String) input.get("toolUseId"),
                enumValue(input, "compacted", SessionHistorySearchInput.Compacted.class),
                enumValue(input, "summaryState", SessionHistorySearchInput.SummaryState.class),
                (Integer) input.get("limit"),
                (String) input.get("cursor"));
    }

    public SessionHistoryReadInput validateReadInput(Map<String, Object> rawInput) {
        Map<String, Object> input = validateRead(rawInput);
        SessionHistoryReadInput.SelectorArm arm;
        if (input.containsKey("refs")) arm = SessionHistoryReadInput.SelectorArm.REFS;
        else if (input.containsKey("seqFrom")) arm = SessionHistoryReadInput.SelectorArm.RANGE;
        else if (input.containsKey("aroundSeq")) arm = SessionHistoryReadInput.SelectorArm.AROUND;
        else if (input.containsKey("tail")) arm = SessionHistoryReadInput.SelectorArm.TAIL;
        else arm = SessionHistoryReadInput.SelectorArm.ARCHIVE;
        @SuppressWarnings("unchecked")
        List<String> refs = (List<String>) input.get("refs");
        return new SessionHistoryReadInput(
                arm,
                refs,
                (Long) input.get("seqFrom"),
                (Long) input.get("seqTo"),
                (Long) input.get("aroundSeq"),
                (Integer) input.get("before"),
                (Integer) input.get("after"),
                (Integer) input.get("tail"),
                (String) input.get("archiveRef"),
                (Integer) input.get("offset"),
                (Integer) input.get("maxChars"),
                (String) input.get("cursor"));
    }

    public Map<String, Object> validateSearch(Map<String, Object> rawInput) {
        Map<String, Object> raw = rawInput == null ? Map.of() : rawInput;
        rejectUnknownFields(raw, SEARCH_FIELD_SET);
        Map<String, Object> out = new LinkedHashMap<>();

        copyOptionalQuery(raw, out);
        copyOptionalLong(raw, out, "seqFrom", 0, Long.MAX_VALUE);
        copyOptionalLong(raw, out, "seqTo", 0, Long.MAX_VALUE);
        copyOptionalEnumList(raw, out, "roles", ROLES, 2);
        copyOptionalEnumList(raw, out, "kinds", KINDS, 4);
        copyOptionalString(raw, out, "toolName", 256);
        copyOptionalString(raw, out, "toolUseId", 256);
        copyOptionalEnum(raw, out, "compacted", COMPACTED);
        copyOptionalEnum(raw, out, "summaryState", SUMMARY_STATES);
        copyOptionalInt(raw, out, "limit", 1, 50);
        copyOptionalString(raw, out, "cursor", 4096);

        Long from = (Long) out.get("seqFrom");
        Long to = (Long) out.get("seqTo");
        if (from != null && to != null && from > to) {
            fail("INVALID_RANGE", "seqFrom", "seqFrom must be less than or equal to seqTo");
        }
        if (!hasSearchLocator(out)) {
            fail("MISSING_SELECTOR", null, "Search requires at least one locator or filter");
        }
        return immutable(out);
    }

    public Map<String, Object> validateRead(Map<String, Object> rawInput) {
        Map<String, Object> raw = rawInput == null ? Map.of() : rawInput;
        rejectUnknownFields(raw, READ_FIELD_SET);
        Set<String> selectorFields = new LinkedHashSet<>(raw.keySet());
        selectorFields.remove("cursor");

        Set<String> selectedArm = List.of(
                        Set.of("refs"),
                        Set.of("seqFrom", "seqTo"),
                        Set.of("aroundSeq", "before", "after"),
                        Set.of("tail"),
                        Set.of("archiveRef", "offset", "maxChars"))
                .stream()
                .filter(selectorFields::equals)
                .findFirst()
                .orElse(null);
        if (selectedArm == null) {
            fail("INVALID_SELECTOR", null, "Read requires exactly one complete selector arm");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        if (selectedArm.contains("refs")) {
            out.put("refs", stringList(raw.get("refs"), "refs", 1, 50, 4096, true));
        } else if (selectedArm.contains("seqFrom")) {
            copyRequiredLong(raw, out, "seqFrom", 0, Long.MAX_VALUE);
            copyRequiredLong(raw, out, "seqTo", 0, Long.MAX_VALUE);
            if ((Long) out.get("seqFrom") > (Long) out.get("seqTo")) {
                fail("INVALID_RANGE", "seqFrom", "seqFrom must be less than or equal to seqTo");
            }
        } else if (selectedArm.contains("aroundSeq")) {
            copyRequiredLong(raw, out, "aroundSeq", 0, Long.MAX_VALUE);
            copyRequiredInt(raw, out, "before", 0, 49);
            copyRequiredInt(raw, out, "after", 0, 49);
            if ((Integer) out.get("before") + 1 + (Integer) out.get("after") > 50) {
                fail("OUT_OF_BOUNDS", "before", "around selector may return at most 50 events");
            }
        } else if (selectedArm.contains("tail")) {
            copyRequiredInt(raw, out, "tail", 1, 50);
        } else {
            copyRequiredString(raw, out, "archiveRef", 4096);
            copyRequiredInt(raw, out, "offset", 0, Integer.MAX_VALUE);
            copyRequiredInt(raw, out, "maxChars", 1, 20_000);
        }
        copyOptionalString(raw, out, "cursor", 4096);
        return immutable(out);
    }

    private static boolean hasSearchLocator(Map<String, Object> input) {
        if (nonBlank(input.get("query")) || nonBlank(input.get("toolName"))
                || nonBlank(input.get("toolUseId"))) {
            return true;
        }
        if (input.containsKey("seqFrom") || input.containsKey("seqTo")
                || input.containsKey("roles") || input.containsKey("kinds")) {
            return true;
        }
        return !"ANY".equals(input.get("compacted")) && input.containsKey("compacted")
                || !"ANY".equals(input.get("summaryState")) && input.containsKey("summaryState");
    }

    private static void rejectUnknownFields(Map<?, ?> input, Set<String> allowed) {
        rejectIdentityFieldsDeep(input);
        for (Object rawKey : input.keySet()) {
            if (!(rawKey instanceof String)) {
                fail("UNKNOWN_FIELD", null, "History fields must have string names");
            }
            String key = (String) rawKey;
            if (!allowed.contains(key)) {
                fail("UNKNOWN_FIELD", key, "Unknown History field: " + key);
            }
            if (input.get(key) == null) {
                fail("INVALID_TYPE", key, "History field cannot be null: " + key);
            }
        }
    }

    private static void rejectIdentityFieldsDeep(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object rawKey = entry.getKey();
                if (rawKey instanceof String key) {
                    String normalized = key.chars()
                            .filter(Character::isLetterOrDigit)
                            .collect(StringBuilder::new,
                                    (builder, character) -> builder.append((char) character),
                                    StringBuilder::append)
                            .toString()
                            .toLowerCase(Locale.ROOT);
                    if (normalized.equals("sessionid") || normalized.equals("userid")) {
                        fail("IDENTITY_FIELD_FORBIDDEN", key,
                                "History identity comes from trusted runtime scope");
                    }
                }
                rejectIdentityFieldsDeep(entry.getValue());
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) rejectIdentityFieldsDeep(item);
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Enum<E>> List<E> enumList(
            Map<String, Object> input, String field, Class<E> type) {
        List<String> values = (List<String>) input.get(field);
        if (values == null) return null;
        return values.stream().map(value -> Enum.valueOf(type, value)).toList();
    }

    private static <E extends Enum<E>> E enumValue(
            Map<String, Object> input, String field, Class<E> type) {
        String value = (String) input.get(field);
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static void copyOptionalString(
            Map<String, Object> raw, Map<String, Object> out, String field, int maxLength) {
        if (raw.containsKey(field)) copyRequiredString(raw, out, field, maxLength);
    }

    private static void copyOptionalQuery(Map<String, Object> raw, Map<String, Object> out) {
        if (!raw.containsKey("query")) return;
        Object value = raw.get("query");
        if (!(value instanceof String)) {
            fail("INVALID_TYPE", "query", "query must be a string");
        }
        String query = (String) value;
        if (query.isBlank() || query.length() > 512) {
            fail("OUT_OF_BOUNDS", "query", "query must be nonblank and at most 512 chars");
        }
        out.put("query", query);
    }

    private static void copyRequiredString(
            Map<String, Object> raw, Map<String, Object> out, String field, int maxLength) {
        Object value = raw.get(field);
        if (!(value instanceof String)) {
            fail("INVALID_TYPE", field, field + " must be a string");
        }
        String string = (String) value;
        if (string.isBlank() || string.length() > maxLength) {
            fail("OUT_OF_BOUNDS", field, field + " must be nonblank and at most " + maxLength + " chars");
        }
        out.put(field, string);
    }

    private static void copyOptionalLong(Map<String, Object> raw, Map<String, Object> out,
                                         String field, long min, long max) {
        if (raw.containsKey(field)) copyRequiredLong(raw, out, field, min, max);
    }

    private static void copyRequiredLong(Map<String, Object> raw, Map<String, Object> out,
                                         String field, long min, long max) {
        long value = integralLong(raw.get(field), field);
        if (value < min || value > max) {
            fail("OUT_OF_BOUNDS", field, field + " is outside its permitted range");
        }
        out.put(field, value);
    }

    private static void copyOptionalInt(Map<String, Object> raw, Map<String, Object> out,
                                        String field, int min, int max) {
        if (raw.containsKey(field)) copyRequiredInt(raw, out, field, min, max);
    }

    private static void copyRequiredInt(Map<String, Object> raw, Map<String, Object> out,
                                        String field, int min, int max) {
        long value = integralLong(raw.get(field), field);
        if (value < min || value > max) {
            fail("OUT_OF_BOUNDS", field, field + " is outside its permitted range");
        }
        out.put(field, (int) value);
    }

    private static void copyOptionalEnum(Map<String, Object> raw, Map<String, Object> out,
                                         String field, Set<String> values) {
        if (!raw.containsKey(field)) return;
        Object value = raw.get(field);
        if (!(value instanceof String)) {
            fail("INVALID_TYPE", field, field + " must be a string");
        }
        String string = (String) value;
        if (!values.contains(string)) {
            fail("INVALID_ENUM", field, "Unsupported " + field + " value");
        }
        out.put(field, string);
    }

    private static void copyOptionalEnumList(Map<String, Object> raw, Map<String, Object> out,
                                             String field, Set<String> values, int maxItems) {
        if (!raw.containsKey(field)) return;
        Object rawValue = raw.get(field);
        if (!(rawValue instanceof List<?>)) {
            fail("INVALID_TYPE", field, field + " must be an array");
        }
        List<?> list = (List<?>) rawValue;
        if (list.isEmpty() || list.size() > maxItems) {
            fail("OUT_OF_BOUNDS", field, field + " has an invalid item count");
        }
        List<String> normalized = new ArrayList<>(list.size());
        Set<String> unique = new LinkedHashSet<>();
        for (Object value : list) {
            if (!(value instanceof String)) {
                fail("INVALID_TYPE", field, field + " items must be strings");
            }
            String string = (String) value;
            if (!values.contains(string)) {
                fail("INVALID_ENUM", field, "Unsupported " + field + " value");
            }
            if (!unique.add(string)) {
                fail("INVALID_ENUM", field, field + " items must be unique");
            }
            normalized.add(string);
        }
        out.put(field, List.copyOf(normalized));
    }

    private static List<String> stringList(Object rawValue, String field, int minItems,
                                           int maxItems, int maxLength, boolean uniqueOnly) {
        if (!(rawValue instanceof List<?>)) {
            fail("INVALID_TYPE", field, field + " must be an array");
        }
        List<?> list = (List<?>) rawValue;
        if (list.size() < minItems || list.size() > maxItems) {
            fail("OUT_OF_BOUNDS", field, field + " has an invalid item count");
        }
        List<String> out = new ArrayList<>(list.size());
        Set<String> unique = new LinkedHashSet<>();
        for (Object value : list) {
            if (!(value instanceof String)) {
                fail("INVALID_TYPE", field, field + " items must be strings");
            }
            String string = (String) value;
            if (string.isBlank() || string.length() > maxLength) {
                fail("OUT_OF_BOUNDS", field, field + " contains an invalid string");
            }
            if (uniqueOnly && !unique.add(string)) {
                continue;
            }
            out.add(string);
        }
        return List.copyOf(out);
    }

    private static long integralLong(Object value, String field) {
        try {
            if (value instanceof Byte || value instanceof Short || value instanceof Integer
                    || value instanceof Long || value instanceof BigInteger) {
                return new BigInteger(value.toString()).longValueExact();
            }
            if (value instanceof BigDecimal decimal) {
                return decimal.toBigIntegerExact().longValueExact();
            }
        } catch (ArithmeticException ignored) {
            fail("OUT_OF_BOUNDS", field, field + " is outside the integer range");
        }
        fail("INVALID_TYPE", field, field + " must be an integer");
        return 0;
    }

    private static boolean nonBlank(Object value) {
        return value instanceof String string && !string.isBlank();
    }

    private static Map<String, Object> immutable(Map<String, Object> value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    private static void fail(String code, String field, String message) {
        throw new HistoryInputValidationException(code, field, message);
    }
}
