package com.skillforge.server.util;

/**
 * Utility for converting float arrays to/from pgvector string format and
 * computing cosine similarity in pure Java.
 */
public final class VectorUtils {

    private VectorUtils() {}

    /**
     * Convert a float array to pgvector string format: "[0.1,0.2,...]"
     */
    public static String toVectorString(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        return sb.append(']').toString();
    }

    /**
     * Parse a pgvector text format "[0.1,0.2,...]" (the form produced by
     * {@code embedding::text} casts) back to a {@code float[]}. Returns {@code null}
     * for null / blank / structurally invalid input so the caller can simply skip the
     * row instead of throwing.
     */
    public static float[] parseVectorString(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // Strip surrounding "[" / "]" if present
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            return new float[0];
        }
        String[] parts = trimmed.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return result;
    }

    /**
     * Cosine similarity in {@code [-1.0, 1.0]} (1.0 = identical direction).
     * Returns 0.0 for null / mismatched-length / zero-magnitude inputs so the caller
     * never has to special-case those (they simply fall under any reasonable similarity
     * threshold and will not be treated as duplicates).
     */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            double x = a[i];
            double y = b[i];
            dot += x * y;
            normA += x * x;
            normB += y * y;
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
