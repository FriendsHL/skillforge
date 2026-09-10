package com.skillforge.server.history;

import java.util.Objects;

/** Unicode code-point helpers shared by History projection and pagination. */
public final class HistoryUnicode {

    private HistoryUnicode() {
    }

    public static String sliceByCodePoints(String value, int offset, int maxCodePoints) {
        Objects.requireNonNull(value, "value");
        if (offset < 0 || maxCodePoints < 0) {
            throw new IllegalArgumentException("History code-point offsets must be non-negative");
        }
        int codePointCount = value.codePointCount(0, value.length());
        if (offset > codePointCount) {
            throw new IllegalArgumentException("History code-point offset exceeds content length");
        }
        int endOffset = Math.min(codePointCount, Math.addExact(offset, maxCodePoints));
        int startIndex = value.offsetByCodePoints(0, offset);
        int endIndex = value.offsetByCodePoints(0, endOffset);
        return value.substring(startIndex, endIndex);
    }

    public static int codePointLength(String value) {
        Objects.requireNonNull(value, "value");
        return value.codePointCount(0, value.length());
    }
}
