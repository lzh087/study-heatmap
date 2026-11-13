package com.study.heatmap.tracker;

/** 某一拍里「屏幕上正在显示哪些行」的快照。 */
final class EditorSnapshot {

    final String relPath;
    /** 与可见行一一对应；空白行为 null（空白行不计时） */
    final String[] keys;
    /** 可见区域第一行的逻辑行号（1-based，仅用于判断是否发生翻页） */
    final int fromLine;
    final int visibleLines;

    EditorSnapshot(String relPath, String[] keys, int fromLine, int visibleLines) {
        this.relPath = relPath;
        this.keys = keys;
        this.fromLine = fromLine;
        this.visibleLines = visibleLines;
    }
}
