package com.study.heatmap.tracker;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.study.heatmap.core.Heat;
import com.study.heatmap.core.Hashing;
import com.study.heatmap.core.Journal;
import com.study.heatmap.core.Ledger;
import com.study.heatmap.core.LedgerStore;
import com.study.heatmap.core.ReportRenderer;
import com.study.heatmap.ui.CloseChoiceDialog;
import com.study.heatmap.ui.ReportOpener;
import com.study.heatmap.ui.StudyStatusBarWidget;
import org.jetbrains.annotations.NotNull;

import java.awt.Point;
import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 学习计时服务（每个项目一份）。
 *
 * 计时口径：
 *  1. 每 1 秒一拍，把这一拍记到「当前屏幕上可见的每一行」上（每行各记 1 秒）。
 *  2. 滚轮滚动后 800ms 内不计；可见区首行变化（翻页/跳转）的那一拍也不计。
 *  3. IDE 窗口不在前台不计。
 *  4. 键鼠（含触控板事件）连续 10 分钟无操作视为暂离：停止计时，并把「最后一次操作之后」
 *     记下的那些拍回退掉——即账本回到你最后一次真正动手的状态。
 *  5. 关闭项目时弹窗让你选择「保留，下次继续」或「结算并生成报告」。
 */
public final class StudyTrackerService {

    private static final Logger LOG = Logger.getInstance(StudyTrackerService.class);

    /** 暂离阈值：键鼠 10 分钟无操作。 */
    public static final long IDLE_MS = 10 * 60 * 1000L;
    private static final long TICK_MS = 1000L;
    private static final long WHEEL_PAUSE_MS = 800L;
    private static final long SAVE_EVERY_MS = 60_000L;
    private static final long ROLLBACK_MARGIN_MS = 5_000L;
    private static final int MAX_INDEXED_LINES = 20_000;

    private final Project project;
    private final Object lock = new Object();

    private volatile boolean running;
    private ScheduledExecutorService executor;
    private ActivityMonitor activity;
    private LedgerStore store;
    private volatile StudyStatusBarWidget widget;

    /** 回退日志：只保留最近 IDLE_MS 内的拍，更早的已经不可能被回退。 */
    private final Journal journal = new Journal();

    private Path projectRoot;
    private long lastTickAt;
    private long lastSaveAt;
    private long sessionStartAt;
    private double sessionSeconds;
    private double pageSeconds;
    private boolean away;
    private String lastFile;
    private int lastFromLine = -1;
    private String lastStatus;

    private Document indexedDoc;
    private long indexedStamp = -1L;
    private Map<String, int[]> indexByAnchor = Map.of();

    public StudyTrackerService(Project project) {
        this.project = project;
    }

    public static StudyTrackerService getInstance(Project project) {
        return project.getService(StudyTrackerService.class);
    }

    public boolean isRunning() {
        return running;
    }

    public void attachWidget(StudyStatusBarWidget w) {
        this.widget = w;
        refreshStatus(System.currentTimeMillis(), true);
    }

    public double sessionSeconds() {
        return sessionSeconds;
    }

    public double totalSeconds() {
        LedgerStore s = store;
        return s == null ? 0 : s.ledger().totalSeconds();
    }

    public int recordedLines() {
        LedgerStore s = store;
        return s == null ? 0 : s.ledger().recordedLines();
    }

    public int fileCount() {
        LedgerStore s = store;
        return s == null ? 0 : s.ledger().files.size();
    }

    // ------------------------------------------------------------------ life

    public void start() {
        synchronized (lock) {
            if (running) {
                return;
            }
            String base = project.getBasePath();
            if (base == null) {
                return;
            }
            try {
                store = LedgerStore.open(Path.of(base));
            } catch (Exception e) {
                LOG.warn("StudyHeatmap: 无法初始化账本", e);
                return;
            }
            activity = ApplicationManager.getApplication().getService(ActivityMonitor.class);
            if (activity == null) {
                return;
            }
            running = true;
            projectRoot = Path.of(base);
            sessionStartAt = System.currentTimeMillis();
            lastTickAt = sessionStartAt;
            lastSaveAt = sessionStartAt;
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "study-heatmap-tick");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
            executor.scheduleWithFixedDelay(this::tickSafely, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);

            project.getMessageBus().connect(project).subscribe(ProjectManager.TOPIC, new ProjectManagerListener() {
                @Override
                public void projectClosing(@NotNull Project p) {
                    onProjectClosing();
                }
            });
        }
    }

    private void tickSafely() {
        try {
            tick();
        } catch (Throwable t) {
            LOG.debug("StudyHeatmap: tick failed", t);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        double dt = (now - lastTickAt) / 1000.0;
        lastTickAt = now;
        if (!running || dt <= 0) {
            return;
        }
        if (dt > 3.0) {
            // 系统休眠 / JVM 卡顿，按一拍计，避免一次记账吃掉几分钟
            dt = 1.0;
        }

        if (!ApplicationManager.getApplication().isActive()) {
            away = false;
            return;
        }

        long lastInput = activity.lastInputAt();
        if (now - lastInput > IDLE_MS) {
            if (!away) {
                rollbackTo(lastInput);
                away = true;
            }
            refreshStatus(now, false);
            return;
        }
        away = false;

        // 滚轮滚动期间不算学习
        if (now - activity.lastWheelAt() < WHEEL_PAUSE_MS) {
            return;
        }

        EditorSnapshot snap = capture();
        if (snap == null) {
            lastFile = null;
            lastFromLine = -1;
            pageSeconds = 0;
            refreshStatus(now, false);
            return;
        }

        boolean samePage = snap.relPath.equals(lastFile) && snap.fromLine == lastFromLine;
        if (!samePage) {
            if (snap.relPath.equals(lastFile)) {
                // 同一个文件里可见区起点变了 → 翻页/跳转，这一拍不计
                lastFromLine = snap.fromLine;
                pageSeconds = 0;
                refreshStatus(now, false);
                return;
            }
            pageSeconds = 0;
        }
        lastFile = snap.relPath;
        lastFromLine = snap.fromLine;

        apply(snap, dt);
        journal.add(new Journal.Batch(now, snap.relPath, snap.keys, dt));
        sessionSeconds += dt;
        pageSeconds += dt;

        pruneJournal(now);
        if (now - lastSaveAt > SAVE_EVERY_MS) {
            lastSaveAt = now;
            saveQuietly();
        }
        refreshStatus(now, false);
    }

    // ------------------------------------------------------------- accounting

    private void apply(EditorSnapshot snap, double dt) {
        LedgerStore s = store;
        if (s == null) {
            return;
        }
        synchronized (lock) {
            Ledger.FileEntry entry = s.ledger().entry(snap.relPath);
            for (String key : snap.keys) {
                if (key != null) {
                    entry.lineSeconds.merge(key, dt, Double::sum);
                }
            }
            entry.totalSeconds += dt;
            entry.lastSeen = System.currentTimeMillis();
        }
    }

    /** 把 cutoff 之后记下的拍回退掉。 */
    private void rollbackTo(long cutoff) {
        LedgerStore s = store;
        if (s == null) {
            return;
        }
        synchronized (lock) {
            double removed = journal.rollbackAfter(s.ledger(), cutoff);
            sessionSeconds = Math.max(0, sessionSeconds - removed);
        }
        pageSeconds = 0;
    }

    private void pruneJournal(long now) {
        journal.pruneBefore(now - IDLE_MS - ROLLBACK_MARGIN_MS);
    }

    // ---------------------------------------------------------------- capture

    private EditorSnapshot capture() {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            return captureOnEdt();
        }
        EditorSnapshot[] box = new EditorSnapshot[1];
        try {
            ApplicationManager.getApplication().invokeAndWait(() -> box[0] = captureOnEdt());
        } catch (Throwable t) {
            return null;
        }
        return box[0];
    }

    private EditorSnapshot captureOnEdt() {
        if (project.isDisposed()) {
            return null;
        }
        VirtualFile baseDir = project.getBaseDir();
        if (baseDir == null) {
            return null;
        }
        FileEditorManager fem = FileEditorManager.getInstance(project);
        Editor editor = fem.getSelectedTextEditor();
        if (editor == null || editor.isDisposed()) {
            return null;
        }
        VirtualFile file = editor.getVirtualFile();
        if (!TrackedFiles.isTracked(file)) {
            return null;
        }
        String rel = VfsUtilCore.getRelativePath(file, baseDir, '/');
        if (rel == null) {
            return null;
        }
        Rectangle area = editor.getScrollingModel().getVisibleArea();
        if (area == null || area.height <= 0) {
            return null;
        }
        final int lineHeight = Math.max(1, editor.getLineHeight());
        final int rows = Math.max(1, area.height / lineHeight);

        // 逐“视觉行”采样，折叠掉的代码不会被算进来
        LinkedHashSet<Integer> visible = new LinkedHashSet<>();
        for (int r = 0; r < rows; r++) {
            int y = area.y + r * lineHeight + lineHeight / 2;
            if (y >= area.y + area.height) {
                break;
            }
            int line = editor.xyToLogicalPosition(new Point(area.x + 2, y)).line;
            visible.add(line);
        }
        if (visible.isEmpty()) {
            return null;
        }

        final Document doc = editor.getDocument();
        return ReadAction.compute(() -> buildSnapshot(doc, rel, visible));
    }

    private EditorSnapshot buildSnapshot(Document doc, String rel, LinkedHashSet<Integer> visible) {
        Map<String, int[]> index = anchorIndex(doc);
        int lineCount = doc.getLineCount();
        List<String> keys = new ArrayList<>(visible.size());
        int from = Integer.MAX_VALUE;
        for (int line : visible) {
            if (line < 0 || line >= lineCount) {
                continue;
            }
            from = Math.min(from, line + 1);
            String text = doc.getText(new TextRange(doc.getLineStartOffset(line), doc.getLineEndOffset(line)));
            if (Hashing.isBlank(text)) {
                keys.add(null);
                continue;
            }
            String anchor = Hashing.anchor(text);
            int occurrence = 0;
            int[] lines = index.get(anchor);
            if (lines != null) {
                int pos = Arrays.binarySearch(lines, line);
                if (pos >= 0) {
                    occurrence = pos;
                }
            }
            keys.add(Hashing.key(anchor, occurrence));
        }
        if (keys.isEmpty()) {
            return null;
        }
        return new EditorSnapshot(rel, keys.toArray(new String[0]), from == Integer.MAX_VALUE ? 0 : from, keys.size());
    }

    /**
     * 行内容 -> 该内容出现的所有行号（升序）。用来给重复行编序号，
     * 只有文档被修改时才重建，平时直接命中缓存。
     */
    private Map<String, int[]> anchorIndex(Document doc) {
        long stamp = doc.getModificationStamp();
        if (doc == indexedDoc && stamp == indexedStamp) {
            return indexByAnchor;
        }
        int lineCount = doc.getLineCount();
        if (lineCount > MAX_INDEXED_LINES) {
            return Map.of();
        }
        Map<String, List<Integer>> tmp = new HashMap<>();
        for (int i = 0; i < lineCount; i++) {
            String text = doc.getText(new TextRange(doc.getLineStartOffset(i), doc.getLineEndOffset(i)));
            if (Hashing.isBlank(text)) {
                continue;
            }
            tmp.computeIfAbsent(Hashing.anchor(text), k -> new ArrayList<>()).add(i);
        }
        Map<String, int[]> out = new HashMap<>(tmp.size() * 2);
        for (Map.Entry<String, List<Integer>> e : tmp.entrySet()) {
            List<Integer> lines = e.getValue();
            int[] arr = new int[lines.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = lines.get(i);
            }
            out.put(e.getKey(), arr);
        }
        indexedDoc = doc;
        indexedStamp = stamp;
        indexByAnchor = out;
        return out;
    }

    // ----------------------------------------------------------------- status

    private void refreshStatus(long now, boolean force) {
        StudyStatusBarWidget w = widget;
        if (w == null) {
            return;
        }
        String text = "学习 " + Heat.clock(sessionSeconds) + " · 本页 " + Heat.duration(pageSeconds);
        if (!force && text.equals(lastStatus)) {
            return;
        }
        lastStatus = text;
        String tip = "本次 " + Heat.duration(sessionSeconds)
                + " · 本页 " + Heat.duration(pageSeconds)
                + " · 项目累计 " + Heat.duration(totalSeconds())
                + "\n点击生成学习热力图";
        ApplicationManager.getApplication().invokeLater(() -> w.update(text, tip));
    }

    // ------------------------------------------------------------------ close

    private void onProjectClosing() {
        synchronized (lock) {
            if (!running) {
                return;
            }
            running = false;
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        double total = sessionSeconds;
        saveQuietly();
        if (total < 5) {
            logSession(total, false);
            return;
        }
        if (isExitInProgress()) {
            // 整个 IDE 正在退出，不弹窗，直接落盘
            logSession(total, false);
            return;
        }
        Runnable show = () -> askCloseChoice(total);
        if (ApplicationManager.getApplication().isDispatchThread()) {
            show.run();
        } else {
            try {
                ApplicationManager.getApplication().invokeAndWait(show);
            } catch (Throwable ignored) {
                // 弹不出来也不能丢数据
            }
        }
    }

    private void askCloseChoice(double sessionTotal) {
        CloseChoiceDialog dialog = new CloseChoiceDialog(
                sessionTotal, totalSeconds(), fileCount(), recordedLines());
        CloseChoiceDialog.Choice choice = dialog.showAndGetChoice();
        boolean settled = choice == CloseChoiceDialog.Choice.SETTLE;
        logSession(sessionTotal, settled);
        if (!settled) {
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                ReportRenderer.Result result = generateReport();
                java.nio.file.Path pdf = ReportOpener.exportPdf(result);
                ApplicationManager.getApplication().invokeLater(() -> {
                    ReportOpener.open(pdf != null ? pdf : result.index());
                    if (!project.isDisposed()) {
                        ReportOpener.notifyReady(project, result, pdf);
                    }
                });
            } catch (Exception e) {
                LOG.warn("StudyHeatmap: 生成报告失败", e);
            }
        });
    }

    private void logSession(double seconds, boolean settled) {
        LedgerStore s = store;
        if (s == null) {
            return;
        }
        s.appendSession(sessionStartAt, System.currentTimeMillis(), seconds, fileCount(), settled);
    }

    private boolean isExitInProgress() {
        try {
            return com.intellij.openapi.application.ex.ApplicationEx.class
                    .cast(ApplicationManager.getApplication()).isExitInProgress();
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ report

    public ReportRenderer.Result generateReport() throws IOException {
        LedgerStore s = store;
        if (s == null) {
            throw new IOException("账本未初始化");
        }
        Path root = projectRoot;
        if (root == null) {
            String base = project.getBasePath();
            if (base == null) {
                throw new IOException("项目没有本地路径");
            }
            root = Path.of(base);
        }
        synchronized (lock) {
            s.save();
            return ReportRenderer.render(root, s.ledger(), root.resolve("study-report"));
        }
    }

    public void saveQuietly() {
        LedgerStore s = store;
        if (s == null) {
            return;
        }
        synchronized (lock) {
            try {
                s.save();
            } catch (Exception e) {
                LOG.debug("StudyHeatmap: 保存失败", e);
            }
        }
    }
}
