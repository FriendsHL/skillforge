package com.skillforge.server.util;

/**
 * Utility for converting float arrays to pgvector string format.
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
}
