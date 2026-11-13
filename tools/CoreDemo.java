package tools;

import com.study.heatmap.core.Hashing;
import com.study.heatmap.core.Heat;
import com.study.heatmap.core.Ledger;
import com.study.heatmap.core.PdfExporter;
import com.study.heatmap.core.ReportRenderer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

/**
 * 开发期验证工具：用一份模拟账本跑真实渲染器，确认输出的热力图形态正确。
 * 不参与插件打包。
 *
 *   javac --release 17 -encoding UTF-8 -d build/core-classes ...
 *   java -cp build/core-classes tools.CoreDemo demo-project demo-report
 */
public final class CoreDemo {

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "demo-project").toAbsolutePath().normalize();
        Path out = Path.of(args.length > 1 ? args[1] : "demo-report").toAbsolutePath().normalize();

        Ledger ledger = simulate(root);
        printTop(ledger);
        ReportRenderer.Result r = ReportRenderer.render(root, ledger, out);

        System.out.println("report   : " + r.index());
        System.out.printf(Locale.ROOT, "total    : %s (%.0f s)%n", Heat.duration(r.totalSeconds()), r.totalSeconds());
        System.out.printf(Locale.ROOT, "files    : %d%n", r.fileCount());
        System.out.printf(Locale.ROOT, "coverage : %d / %d lines%n", r.coveredLines(), r.totalLines());
        Path pdf = PdfExporter.export(r.printHtml(), out.resolve("learning-heatmap.pdf"));
        System.out.println("pdf      : " + (pdf == null ? "(这台机器没有可用浏览器)" : pdf));
        System.out.println();
        System.out.printf("%-58s %10s %10s%n", "file", "seconds", "lines");
        for (ReportRenderer.FileStat s : r.stats()) {
            System.out.printf(Locale.ROOT, "%-58s %10.0f %10s%n",
                    s.relPath, s.seconds, s.covered + "/" + s.lines);
        }
    }

    private static Ledger simulate(Path root) throws Exception {
        Ledger ledger = new Ledger();
        Random rnd = new Random(20261109L);

        List<String> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> p.endsWith(".java") || p.endsWith(".yml") || p.endsWith(".xml"))
                    .sorted()
                    .forEach(files::add);
        }

        for (String rel : files) {
            List<String> lines = Files.readAllLines(root.resolve(rel), StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                continue;
            }
            simulateSession(ledger, rel, lines, rnd);
        }
        return ledger;
    }

    /**
     * 按真实计时语义模拟一次「打开文件、滚动、停留」的阅读过程：
     * 每秒一拍，可见窗口内的每一行各记 1 秒，文件总时长也加 1 秒。
     * 这样生成的热力图和插件真正跑出来的形态是一致的。
     */
    private static void simulateSession(Ledger ledger, String rel, List<String> lines, Random rnd) {
        int n = lines.size();
        int viewport = 34 + rnd.nextInt(16);
        if (viewport > n) {
            viewport = Math.max(4, n);
        }
        String[] anchors = new String[n];
        Map<String, Integer> occ = new HashMap<>();
        for (int i = 0; i < n; i++) {
            String a = Hashing.anchor(lines.get(i));
            int o = occ.merge(a, 1, Integer::sum) - 1;
            anchors[i] = Hashing.isBlank(lines.get(i)) ? null : Hashing.key(a, o);
        }

        boolean test = rel.contains("/test/") || rel.endsWith(".yml") || rel.endsWith(".xml");
        int ticks = test ? 60 + rnd.nextInt(140) : 180 + rnd.nextInt(600);
        int cursor = 0;
        int dwell = 4 + rnd.nextInt(10);

        Ledger.FileEntry entry = ledger.entry(rel);
        for (int t = 0; t < ticks; t++) {
            if (t % dwell == 0) {
                int r = rnd.nextInt(100);
                if (r < 52) {
                    cursor += 1;
                } else if (r < 68) {
                    cursor += 4 + rnd.nextInt(18);
                } else if (r < 76) {
                    cursor -= 2 + rnd.nextInt(12);
                }
                // 其余情况原地不动：正在啃某一段
            }
            cursor = Math.max(0, Math.min(Math.max(0, n - viewport), cursor));

            int end = Math.min(n, cursor + viewport);
            for (int i = cursor; i < end; i++) {
                if (anchors[i] != null) {
                    entry.lineSeconds.merge(anchors[i], 1.0, Double::sum);
                }
            }
            entry.totalSeconds += 1.0;
        }
        entry.lastSeen = System.currentTimeMillis();
    }

    private static void printTop(Ledger ledger) {
        record Row(String file, String key, double sec) {
        }
        List<Row> rows = new ArrayList<>();
        ledger.files.forEach((f, e) -> e.lineSeconds.forEach((k, v) -> rows.add(new Row(f, k, v))));
        rows.sort(Comparator.comparingDouble(Row::sec).reversed());
        for (int i = 0; i < Math.min(5, rows.size()); i++) {
            System.out.printf(Locale.ROOT, "  top %d: %s %s %.0fs%n",
                    i + 1, rows.get(i).file, rows.get(i).key, rows.get(i).sec);
        }
    }
}
