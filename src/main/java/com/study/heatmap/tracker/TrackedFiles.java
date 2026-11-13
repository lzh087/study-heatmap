package com.study.heatmap.tracker;

import com.intellij.openapi.vfs.VirtualFile;

import java.util.Locale;
import java.util.Set;

/** 哪些文件值得统计。构建产物和二进制直接跳过。 */
public final class TrackedFiles {

    private static final Set<String> EXTENSIONS = Set.of(
            "java", "kt", "kts", "gradle", "xml", "properties", "sql",
            "md", "yml", "yaml", "json", "txt"
    );

    private static final Set<String> SKIPPED_DIRS = Set.of(
            "build", "target", "out", "node_modules", ".git", ".gradle",
            "study-report", ".idea", "bin", "dist", "logs"
    );

    private static final long MAX_BYTES = 2L * 1024 * 1024;

    private TrackedFiles() {
    }

    public static boolean isTracked(VirtualFile file) {
        if (file == null || !file.isValid() || file.isDirectory()) {
            return false;
        }
        if (file.getLength() > MAX_BYTES) {
            return false;
        }
        String ext = file.getExtension();
        if (ext == null || !EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT))) {
            return false;
        }
        VirtualFile dir = file.getParent();
        while (dir != null && !dir.getName().isEmpty()) {
            if (SKIPPED_DIRS.contains(dir.getName())) {
                return false;
            }
            dir = dir.getParent();
        }
        return true;
    }
}
