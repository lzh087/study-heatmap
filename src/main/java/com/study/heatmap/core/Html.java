package com.study.heatmap.core;

/** HTML 转义小工具。 */
public final class Html {

    private Html() {
    }

    public static String escape(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            sb.append(escapeChar(s.charAt(i)));
        }
        return sb.toString();
    }

    public static String escapeChar(char c) {
        return switch (c) {
            case '&' -> "&amp;";
            case '<' -> "&lt;";
            case '>' -> "&gt;";
            case '"' -> "&quot;";
            case '\'' -> "&#39;";
            default -> String.valueOf(c);
        };
    }
}
