package com.study.heatmap.core;

import java.util.Set;

/**
 * 极简 Java 词法着色。不依赖任何库，也不需要 IDE 的 PSI，
 * 这样报告在 IDE 之外（浏览器、别人的电脑）也能独立打开。
 */
public final class JavaLexer {

    public static final class LexState {
        public boolean inBlockComment;
    }

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "var", "record", "sealed", "permits", "yield", "true", "false", "null"
    );

    private JavaLexer() {
    }

    public static String highlight(String s, LexState st) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 48);
        int i = 0;
        int n = s.length();
        while (i < n) {
            if (st.inBlockComment) {
                int end = s.indexOf("*/", i);
                if (end < 0) {
                    span(out, "cm", s.substring(i));
                    i = n;
                } else {
                    span(out, "cm", s.substring(i, end + 2));
                    i = end + 2;
                    st.inBlockComment = false;
                }
                continue;
            }
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                span(out, "cm", s.substring(i));
                break;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                st.inBlockComment = true;
                continue;
            }
            if (c == '"') {
                int j = i + 1;
                while (j < n) {
                    char d = s.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    if (d == '"') {
                        j++;
                        break;
                    }
                    j++;
                }
                j = Math.min(j, n);
                span(out, "st", s.substring(i, j));
                i = j;
                continue;
            }
            if (c == '\'') {
                int j = i + 1;
                while (j < n) {
                    char d = s.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    if (d == '\'') {
                        j++;
                        break;
                    }
                    j++;
                }
                j = Math.min(j, n);
                span(out, "st", s.substring(i, j));
                i = j;
                continue;
            }
            if (Character.isDigit(c)) {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '.' || s.charAt(j) == '_')) {
                    j++;
                }
                span(out, "nu", s.substring(i, j));
                i = j;
                continue;
            }
            if (c == '@' && i + 1 < n && Character.isJavaIdentifierStart(s.charAt(i + 1))) {
                int j = i + 1;
                while (j < n && Character.isJavaIdentifierPart(s.charAt(j))) {
                    j++;
                }
                span(out, "an", s.substring(i, j));
                i = j;
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int j = i;
                while (j < n && Character.isJavaIdentifierPart(s.charAt(j))) {
                    j++;
                }
                String word = s.substring(i, j);
                if (KEYWORDS.contains(word)) {
                    span(out, "kw", word);
                } else {
                    out.append(word);
                }
                i = j;
                continue;
            }
            out.append(Html.escapeChar(c));
            i++;
        }
        return out.toString();
    }

    private static void span(StringBuilder out, String cls, String text) {
        if (text.isEmpty()) {
            return;
        }
        out.append("<span class=\"").append(cls).append("\">").append(Html.escape(text)).append("</span>");
    }
}
