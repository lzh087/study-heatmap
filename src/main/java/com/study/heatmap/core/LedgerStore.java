package com.study.heatmap.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 账本落盘。数据放在用户目录下，不污染被统计的仓库：
 *   ~/.study-tracker/projects/<项目名>-<路径哈希>/ledger.json
 * 同一个项目多次打开会命中同一个目录，因此天然支持「一次学不完，下次接着累计」。
 */
public final class LedgerStore {

    private static final String LEDGER = "ledger.json";
    private static final String SESSIONS = "sessions.jsonl";
    private static final String META = "project.json";

    private final Path dir;
    private final Ledger ledger = new Ledger();

    private LedgerStore(Path dir) {
        this.dir = dir;
    }

    public static Path baseDir() {
        String home = System.getProperty("user.home", ".");
        return Paths.get(home, ".study-tracker");
    }

    public static LedgerStore open(Path projectRoot) throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();
        String name = root.getFileName() == null ? "project" : root.getFileName().toString();
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        String hash = Hashing.anchor(root.toString());
        Path dir = baseDir().resolve("projects").resolve(safe + "-" + hash.substring(0, 8));
        Files.createDirectories(dir);
        LedgerStore store = new LedgerStore(dir);
        store.writeMeta(root);
        store.load();
        return store;
    }

    public Path dir() {
        return dir;
    }

    public Ledger ledger() {
        return ledger;
    }

    private void writeMeta(Path root) {
        try {
            Path meta = dir.resolve(META);
            if (Files.exists(meta)) {
                return;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", root.toString());
            m.put("name", root.getFileName() == null ? "" : root.getFileName().toString());
            m.put("createdAt", System.currentTimeMillis());
            Files.writeString(meta, Json.write(m), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 元信息写不进去不影响统计
        }
    }

    private void load() throws IOException {
        Path f = dir.resolve(LEDGER);
        if (!Files.exists(f)) {
            return;
        }
        String text;
        try {
            text = Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }
        if (text.isBlank()) {
            return;
        }
        Map<String, Object> root = Json.asMap(Json.parse(text));
        Map<String, Object> files = Json.asMap(root.get("files"));
        for (Map.Entry<String, Object> e : files.entrySet()) {
            Map<String, Object> fe = Json.asMap(e.getValue());
            Ledger.FileEntry entry = ledger.entry(e.getKey());
            entry.totalSeconds = Json.num(fe, "total", 0);
            entry.lastSeen = Json.lng(fe, "lastSeen", 0);
            entry.sessions = (int) Json.num(fe, "sessions", 0);
            Map<String, Object> lines = Json.asMap(fe.get("lines"));
            for (Map.Entry<String, Object> l : lines.entrySet()) {
                Object v = l.getValue();
                if (v instanceof Number num) {
                    entry.lineSeconds.put(l.getKey(), num.doubleValue());
                }
            }
        }
    }

    public synchronized void save() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        root.put("savedAt", System.currentTimeMillis());
        Map<String, Object> files = new LinkedHashMap<>();
        for (Map.Entry<String, Ledger.FileEntry> e : ledger.files.entrySet()) {
            Ledger.FileEntry fe = e.getValue();
            if (fe.lineSeconds.isEmpty() && fe.totalSeconds <= 0) {
                continue;
            }
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("total", round(fe.totalSeconds));
            o.put("lastSeen", fe.lastSeen);
            o.put("sessions", fe.sessions);
            Map<String, Object> lines = new LinkedHashMap<>();
            for (Map.Entry<String, Double> l : fe.lineSeconds.entrySet()) {
                lines.put(l.getKey(), round(l.getValue()));
            }
            o.put("lines", lines);
            files.put(e.getKey(), o);
        }
        root.put("files", files);

        Path target = dir.resolve(LEDGER);
        Path tmp = dir.resolve(LEDGER + ".tmp");
        Files.writeString(tmp, Json.write(root), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 记录一次学习会话，用于「多学几次」的追溯。 */
    public void appendSession(long startAt, long endAt, double seconds, int fileCount, boolean settled) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("start", startAt);
            m.put("end", endAt);
            m.put("seconds", round(seconds));
            m.put("files", fileCount);
            m.put("settled", settled);
            Files.writeString(dir.resolve(SESSIONS), Json.write(m) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException ignored) {
            // 会话日志只是辅助信息
        }
    }

    public String exportJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("exportedAt", System.currentTimeMillis());
        Map<String, Object> files = new LinkedHashMap<>();
        for (Map.Entry<String, Ledger.FileEntry> e : ledger.files.entrySet()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("total", round(e.getValue().totalSeconds));
            o.put("lastSeen", e.getValue().lastSeen);
            o.put("lines", new LinkedHashMap<>(e.getValue().lineSeconds));
            files.put(e.getKey(), o);
        }
        root.put("files", files);
        return Json.write(root);
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
