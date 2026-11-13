package com.study.heatmap.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 学习时长账本：文件 -> (行锚点 -> 秒数)。
 * 跨会话累加，不按项目打开次数清零。
 */
public final class Ledger {

    public static final class FileEntry {
        /** key = anchor + "#" + occurrence */
        public final Map<String, Double> lineSeconds = new LinkedHashMap<>();
        public double totalSeconds;
        public long lastSeen;
        /** 该文件被记录过的会话数（近似） */
        public int sessions;

        public void add(String key, double seconds) {
            if (seconds <= 0) {
                return;
            }
            lineSeconds.merge(key, seconds, Double::sum);
            totalSeconds += seconds;
        }
    }

    public final Map<String, FileEntry> files = new LinkedHashMap<>();

    public FileEntry entry(String relPath) {
        return files.computeIfAbsent(relPath, k -> new FileEntry());
    }

    public FileEntry peek(String relPath) {
        return files.get(relPath);
    }

    public double totalSeconds() {
        double sum = 0;
        for (FileEntry e : files.values()) {
            sum += e.totalSeconds;
        }
        return sum;
    }

    public int recordedLines() {
        int n = 0;
        for (FileEntry e : files.values()) {
            n += e.lineSeconds.size();
        }
        return n;
    }

    public void removeLine(String relPath, String key, double seconds) {
        FileEntry f = files.get(relPath);
        if (f == null) {
            return;
        }
        Double cur = f.lineSeconds.get(key);
        if (cur == null) {
            return;
        }
        double next = cur - seconds;
        if (next <= 0.0009) {
            f.lineSeconds.remove(key);
        } else {
            f.lineSeconds.put(key, next);
        }
    }
}
