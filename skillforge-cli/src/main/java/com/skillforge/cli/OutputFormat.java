package com.skillforge.cli;

import java.util.List;

/** Output format enum plus small rendering helpers. */
public enum OutputFormat {
    HUMAN, JSON, YAML;

    /** Render a simple left-aligned table given headers and rows. */
    public static String renderTable(List<String> headers, List<List<String>> rows) {
        int cols = headers.size();
        int[] widths = new int[cols];
        for (int i = 0; i < cols; i++) widths[i] = headers.get(i).length();
        for (List<String> row : rows) {
            for (int i = 0; i < cols && i < row.size(); i++) {
                String v = row.get(i) == null ? "" : row.get(i);
                if (v.length() > widths[i]) widths[i] = v.length();
            }
        }
        StringBuilder sb = new StringBuilder();
        appendRow(sb, headers, widths);
        // separator
        for (int i = 0; i < cols; i++) {
            sb.append("-".repeat(widths[i]));
            if (i < cols - 1) sb.append("  ");
        }
        sb.append('\n');
        for (List<String> row : rows) {
            appendRow(sb, row, widths);
        }
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, List<String> row, int[] widths) {
        for (int i = 0; i < widths.length; i++) {
            String v = i < row.size() && row.get(i) != null ? row.get(i) : "";
            sb.append(v);
            sb.append(" ".repeat(Math.max(0, widths[i] - v.length())));
            if (i < widths.length - 1) sb.append("  ");
        }
        sb.append('\n');
    }
}
