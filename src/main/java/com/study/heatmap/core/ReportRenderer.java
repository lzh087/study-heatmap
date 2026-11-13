package com.study.heatmap.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把账本渲染成「学习热力图」：
 *   study-report/
 *     index.html                            总览 + 目录树 + 排行
 *     src/main/java/com/demo/A.java.html    与源文件 1:1 对应，行背景按停留时长染色
 *
 * 之所以不直接给 .java 文件染色：纯文本没有「背景色」这一说，改文件只会把它弄坏。
 * 所以这里生成的是「目录结构完全一致的 HTML 镜像」。
 */
public final class ReportRenderer {

    public static final class FileStat {
        public final String relPath;
        public final double seconds;
        public final int covered;
        public final int lines;
        public final double maxLine;

        FileStat(String relPath, double seconds, int covered, int lines, double maxLine) {
            this.relPath = relPath;
            this.seconds = seconds;
            this.covered = covered;
            this.lines = lines;
            this.maxLine = maxLine;
        }
    }

    public record Result(Path index, Path printHtml, double totalSeconds, int fileCount, int coveredLines,
                         int totalLines, List<FileStat> stats) {
    }

    private ReportRenderer() {
    }

    public static Result render(Path projectRoot, Ledger ledger, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        List<FileStat> stats = new ArrayList<>();
        int coveredAll = 0;
        int linesAll = 0;
        StringBuilder printSections = new StringBuilder();

        List<Map.Entry<String, Ledger.FileEntry>> entries = new ArrayList<>(ledger.files.entrySet());
        entries.sort(Map.Entry.comparingByKey());

        for (Map.Entry<String, Ledger.FileEntry> e : entries) {
            String rel = e.getKey().replace('\\', '/');
            Path src = projectRoot.resolve(rel);
            if (!Files.isRegularFile(src)) {
                continue;
            }
            List<String> lines = readLines(src);
            if (lines.isEmpty()) {
                continue;
            }

            Map<String, Integer> occ = new HashMap<>();
            double[] secs = new double[lines.size()];
            int covered = 0;
            double max = 0;
            for (int i = 0; i < lines.size(); i++) {
                String raw = lines.get(i);
                String anchor = Hashing.anchor(raw);
                int o = occ.merge(anchor, 1, Integer::sum) - 1;
                if (Hashing.isBlank(raw)) {
                    continue;
                }
                Double s = e.getValue().lineSeconds.get(Hashing.key(anchor, o));
                if (s != null && s > 0.05) {
                    secs[i] = s;
                    covered++;
                    if (s > max) {
                        max = s;
                    }
                }
            }
            if (max <= 0) {
                continue;
            }
            coveredAll += covered;
            linesAll += lines.size();

            Path page = outDir.resolve(rel + ".html");
            Files.createDirectories(page.getParent());
            Files.writeString(page, renderFilePage(rel, lines, secs, max, e.getValue().totalSeconds, covered),
                    StandardCharsets.UTF_8);
            stats.add(new FileStat(rel, e.getValue().totalSeconds, covered, lines.size(), max));

            printSections.append("<section class=\"psec\"><h2 class=\"pfile\">")
                    .append(Html.escape(rel)).append("</h2><div class=\"pmeta\">累计 ")
                    .append(Heat.duration(e.getValue().totalSeconds))
                    .append(" · 覆盖 ").append(covered).append("/").append(lines.size()).append(" 行")
                    .append(" · 单行最深 ").append(Heat.duration(max)).append("</div>")
                    .append(codeBlock(rel, lines, secs, max))
                    .append("</section>");
        }

        stats.sort(Comparator.comparingDouble((FileStat s) -> s.seconds).reversed());

        String projectName = projectRoot.getFileName() == null ? "project" : projectRoot.getFileName().toString();
        Files.writeString(outDir.resolve("index.html"), renderIndex(projectName, stats, ledger, coveredAll, linesAll),
                StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("data.json"), exportJson(ledger), StandardCharsets.UTF_8);
        Path printHtml = outDir.resolve("_print.html");
        Files.writeString(printHtml, renderPrintDocument(projectName, stats, ledger, coveredAll, linesAll,
                printSections.toString()), StandardCharsets.UTF_8);

        return new Result(outDir.resolve("index.html"), printHtml, ledger.totalSeconds(), stats.size(),
                coveredAll, linesAll, stats);
    }

    // ---------------------------------------------------------------- pages

    private static String renderFilePage(String rel, List<String> lines, double[] secs, double max,
                                         double total, int covered) {
        int depth = Math.max(0, rel.split("/").length - 1);
        String up = "../".repeat(depth);
        String name = rel.substring(rel.lastIndexOf('/') + 1);

        StringBuilder sb = new StringBuilder(Math.max(4096, lines.size() * 140));
        sb.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(Html.escape(name)).append(" · 学习热力图</title>")
                .append("<style>").append(css()).append("</style></head><body>");

        sb.append("<header class=\"bar\"><div class=\"bar-in\">")
                .append("<a class=\"back\" href=\"").append(up).append("index.html\">← 总览</a>")
                .append("<span class=\"path\">").append(Html.escape(rel)).append("</span>")
                .append("<span class=\"chips\">")
                .append(chip("累计 " + Heat.duration(total)))
                .append(chip("覆盖 " + covered + "/" + lines.size() + " 行"))
                .append(chip("单行最深 " + Heat.duration(max)))
                .append("</span></div></header>");

        sb.append("<div class=\"legend\"><span>停留短</span><span class=\"grad\"></span><span>停留长</span>")
                .append("<span class=\"hint\">悬停某一行可看具体秒数</span></div>");

        sb.append(codeBlock(rel, lines, secs, max));
        sb.append("</body></html>");
        return sb.toString();
    }

    /** 代码块：行号 + 热力背景 + 语法着色。单文件页和 PDF 汇总页共用。 */
    private static String codeBlock(String rel, List<String> lines, double[] secs, double max) {
        boolean code = isCode(rel);
        StringBuilder sb = new StringBuilder(Math.max(2048, lines.size() * 130));
        sb.append("<div class=\"code\">");
        JavaLexer.LexState state = new JavaLexer.LexState();
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String body = code ? JavaLexer.highlight(raw, state) : Html.escape(raw);
            String bg = Heat.lineBackground(secs[i], max);
            String gut = Heat.gutterColor(secs[i], max);
            sb.append("<div class=\"ln\">")
                    .append("<span class=\"gut\" style=\"background:").append(gut).append("\"></span>")
                    .append("<span class=\"no\">").append(i + 1).append("</span>")
                    .append("<span class=\"cd\"");
            if (!bg.isEmpty()) {
                sb.append(" style=\"background:").append(bg).append("\"");
            }
            sb.append(" title=\"")
                    .append(secs[i] > 0 ? String.format(Locale.ROOT, "%.1f 秒", secs[i]) : "未记录")
                    .append("\">")
                    .append(body.isEmpty() ? "&nbsp;" : body)
                    .append("</span></div>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    /**
     * PDF 用的单文件汇总稿：封面 + 文件清单 + 所有文件的染色代码。
     * 由 {@code PdfExporter} 通过 Edge/Chrome 的无头打印转换成 PDF。
     */
    private static String renderPrintDocument(String projectName, List<FileStat> stats, Ledger ledger,
                                              int covered, int totalLines, String sections) {
        double total = ledger.totalSeconds();
        StringBuilder sb = new StringBuilder(Math.max(64 * 1024, sections.length() + 8192));
        sb.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<title>").append(Html.escape(projectName)).append(" · 学习热力图</title>")
                .append("<style>").append(css()).append(indexCss()).append(printCss()).append("</style></head><body>");

        sb.append("<div class=\"pdf-cover\">")
                .append("<p class=\"pdf-kicker\">学习热力图</p>")
                .append("<h1 class=\"pdf-title\">").append(Html.escape(projectName)).append("</h1>")
                .append("<p class=\"hero\">").append(Heat.duration(total)).append("</p>")
                .append("<p class=\"pdf-meta\">累计学习时长 · ").append(stats.size()).append(" 个文件 · 覆盖 ")
                .append(covered).append("/").append(totalLines).append(" 行</p>")
                .append("<p class=\"pdf-note\">统计口径：代码在屏幕上可见即计时，可见的每一行各记 1 秒；"
                        + "滚轮滚动、窗口失焦、键鼠（含触控板）10 分钟无操作均不计入，暂离部分会回退。"
                        + "颜色为对数缩放，越深表示停留越久。</p>")
                .append("</div>");

        sb.append("<table class=\"plist\">");
        for (FileStat s : stats) {
            sb.append("<tr><td class=\"pn\">").append(Html.escape(s.relPath)).append("</td>")
                    .append("<td class=\"pv\">").append(Heat.duration(s.seconds)).append("</td>")
                    .append("<td class=\"pv\">").append(s.covered).append("/").append(s.lines).append("</td></tr>");
        }
        sb.append("</table>");

        sb.append(sections).append("</body></html>");
        return sb.toString();
    }

    private static String printCss() {
        return """
                @page{size:A4;margin:14mm 10mm}
                html,body{background:#fff!important;color:#1a1a1a!important}
                *{-webkit-print-color-adjust:exact;print-color-adjust:exact}
                .pdf-cover{padding:38mm 6mm 10mm}
                .pdf-kicker{font-size:11px;letter-spacing:.18em;color:#999;margin:0 0 10px}
                .pdf-title{font-size:30px;font-weight:500;margin:0 0 8px;color:#111}
                .hero{color:#111!important;font-size:40px}
                .pdf-meta{font-size:11px;color:#555;margin:2px 0 20px}
                .pdf-note{font-size:10px;color:#777;line-height:1.9;max-width:120mm}
                .plist{border-collapse:collapse;table-layout:fixed;width:100%;font-size:10px;margin:6mm 0 0}
                .plist .pn{width:66%}.plist .pv{width:17%}
                .plist td{padding:3px 6px;border-bottom:1px solid #eee;color:#222}
                .plist .pn{font-family:ui-monospace,Consolas,monospace}
                .plist .pv{text-align:right;white-space:nowrap;color:#555;width:22mm}
                .psec{page-break-before:always}
                .pfile{font-size:10px;font-family:ui-monospace,Consolas,monospace;color:#0b57d0;
                       margin:0 0 2px;font-weight:400;word-break:break-all}
                .pmeta{font-size:9px;color:#666;margin:0 0 5px}
                .code{border:none!important;background:#fff!important;margin:0!important;
                      padding:0!important;overflow:visible!important;border-radius:0!important}
                .ln{break-inside:avoid;min-height:auto}
                .no{color:#aaa!important;font-size:8.6px!important;flex:0 0 24px!important;padding:0 6px 0 0!important}
                .cd{font-size:9px!important;line-height:1.55!important;color:#1a1a1a!important;
                    padding:0 0 0 2px!important}
                .kw{color:#0b57d0!important}.cm{color:#7a7a7a!important}.st{color:#0a7d33!important}
                .nu{color:#a34d00!important}.an{color:#8a5a00!important}
                """;
    }

    private static String renderIndex(String projectName, List<FileStat> stats, Ledger ledger,
                                      int covered, int totalLines) {
        double total = ledger.totalSeconds();
        double maxFile = stats.isEmpty() ? 0 : stats.get(0).seconds;
        double maxLine = 0;
        for (FileStat s : stats) {
            maxLine = Math.max(maxLine, s.maxLine);
        }

        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>学习热力图 · ").append(Html.escape(projectName)).append("</title>")
                .append("<style>").append(css()).append(indexCss()).append("</style></head><body>");

        sb.append("<div class=\"wrap\">");
        sb.append("<h1>").append(Html.escape(projectName)).append(" · 学习热力图</h1>")
                .append("<p class=\"sub\">统计口径：代码在屏幕上可见即计时，滚动、失焦、离开 10 分钟不计入</p>");
        sb.append("<p class=\"hero\">").append(Heat.duration(total)).append("</p>")
                .append("<p class=\"sub\" style=\"margin-top:6px\">累计学习时长</p>");

        sb.append("<div class=\"cards\">");
        sb.append(card("已学习文件", String.valueOf(stats.size())));
        sb.append(card("覆盖代码行", covered + " / " + totalLines));
        sb.append(card("覆盖率", totalLines == 0 ? "—" : Math.round(covered * 100.0 / totalLines) + "%"));
        sb.append(card("单行停留之最", Heat.duration(maxLine)));
        sb.append("</div>");

        if (stats.isEmpty()) {
            sb.append("<div class=\"empty\">还没有可渲染的数据。学着学着就热了。</div>");
        } else {
            sb.append("<h2>文件（按停留时长排序）</h2>");

            Map<String, List<FileStat>> byDir = new LinkedHashMap<>();
            for (FileStat s : stats) {
                byDir.computeIfAbsent(dirOf(s.relPath), k -> new ArrayList<>()).add(s);
            }
            List<Map.Entry<String, List<FileStat>>> groups = new ArrayList<>(byDir.entrySet());
            groups.sort((a, b) -> Double.compare(groupTotal(b.getValue()), groupTotal(a.getValue())));

            for (Map.Entry<String, List<FileStat>> g : groups) {
                sb.append("<div class=\"grp\"><h3>")
                        .append(Html.escape(g.getKey().isEmpty() ? "/" : g.getKey()))
                        .append("</h3>");
                for (FileStat s : g.getValue()) {
                    sb.append(fileRow(s, maxFile, true));
                }
                sb.append("</div>");
            }

            sb.append("<div class=\"note\"><b>怎么读这张图</b><br>")
                    .append("· 行背景越红，表示这行在屏幕上停留的时间越久；左侧竖条是同一数据的强化显示。<br>")
                    .append("· 颜色用对数缩放，否则停留最久的那几行会把整张色阶吃满，其余行看不出差别。<br>")
                    .append("· 时间按“行内容哈希”记账，所以改代码、切分支之后热力仍能落回原来那几行，不会整体错位。<br>")
                    .append("· 空白行不计时——留白没有阅读价值，统计进去只会形成没有意义的红块。<br>")
                    .append("· 滚轮滚动、窗口失焦、键鼠 10 分钟无操作（触控板操作不算离开）都不计入，且离开的部分会回退。</div>");
        }

        sb.append("</div></body></html>");
        return sb.toString();
    }

    private static String fileRow(FileStat s, double maxFile, boolean withCoverage) {
        long pct = maxFile <= 0 ? 0 : Math.round(s.seconds * 100.0 / maxFile);
        StringBuilder sb = new StringBuilder();
        sb.append("<a class=\"row\" href=\"").append(urlEncodePath(s.relPath)).append(".html\">")
                .append("<span class=\"nm\">").append(Html.escape(fileName(s.relPath))).append("</span>")
                .append("<span class=\"bar2\"><i style=\"width:").append(Math.max(2, pct)).append("%\"></i></span>")
                .append("<span class=\"met\">").append(Heat.duration(s.seconds));
        if (withCoverage) {
            sb.append(" · ").append(s.covered).append("/").append(s.lines).append(" 行");
        }
        sb.append("</span></a>");
        return sb.toString();
    }

    private static double groupTotal(List<FileStat> files) {
        double sum = 0;
        for (FileStat f : files) {
            sum += f.seconds;
        }
        return sum;
    }

    private static String card(String k, String v) {
        return "<div class=\"card\"><div class=\"k\">" + Html.escape(k) + "</div><div class=\"v\">" + Html.escape(v) + "</div></div>";
    }

    private static String chip(String text) {
        return "<span class=\"chip\">" + Html.escape(text) + "</span>";
    }

    // ---------------------------------------------------------------- helpers

    private static String exportJson(Ledger ledger) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> files = new LinkedHashMap<>();
        for (Map.Entry<String, Ledger.FileEntry> e : ledger.files.entrySet()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("total", e.getValue().totalSeconds);
            o.put("lines", new LinkedHashMap<>(e.getValue().lineSeconds));
            files.put(e.getKey(), o);
        }
        root.put("files", files);
        return Json.write(root);
    }

    static List<String> readLines(Path p) throws IOException {
        byte[] bytes = Files.readAllBytes(p);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        String[] arr = text.split("\n", -1);
        List<String> out = new ArrayList<>(arr.length);
        for (String s : arr) {
            out.add(s.endsWith("\r") ? s.substring(0, s.length() - 1) : s);
        }
        if (!out.isEmpty() && out.get(out.size() - 1).isEmpty() && text.endsWith("\n")) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private static boolean isCode(String rel) {
        String l = rel.toLowerCase(Locale.ROOT);
        return l.endsWith(".java") || l.endsWith(".kt") || l.endsWith(".kts") || l.endsWith(".gradle");
    }

    private static String fileName(String rel) {
        int i = rel.lastIndexOf('/');
        return i < 0 ? rel : rel.substring(i + 1);
    }

    private static String dirOf(String rel) {
        int i = rel.lastIndexOf('/');
        return i < 0 ? "" : rel.substring(0, i);
    }

    /** 只为处理文件名里的空格/中文/井号，避免浏览器把链接截断。 */
    private static String urlEncodePath(String rel) {
        StringBuilder sb = new StringBuilder();
        for (byte b : rel.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '/' || c == '.' || c == '_' || c == '-') {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format(Locale.ROOT, "%02X", c));
            }
        }
        return sb.toString();
    }

    private static String css() {
        return """
                :root{color-scheme:dark}
                *{box-sizing:border-box}
                html,body{margin:0}
                body{background:#0f1115;color:#d7dae2;font:14px/1.6 ui-sans-serif,-apple-system,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif}
                a{color:#8ab4f8;text-decoration:none}
                a:hover{text-decoration:underline}
                .bar{position:sticky;top:0;z-index:10;background:rgba(15,17,21,.94);border-bottom:1px solid rgba(255,255,255,.08)}
                .bar-in{max-width:1180px;margin:0 auto;padding:10px 20px;display:flex;align-items:center;gap:14px;flex-wrap:wrap}
                .back{font-size:13px;color:#9aa0ab}
                .path{font:12.5px/1.4 ui-monospace,Consolas,"Courier New",monospace;color:#e8eaf0;word-break:break-all}
                .chips{display:flex;gap:8px;margin-left:auto;flex-wrap:wrap}
                .chip{font-size:11.5px;color:#c3c8d2;background:#1b1f28;border:1px solid rgba(255,255,255,.07);padding:2px 9px;border-radius:999px}
                .legend{max-width:1180px;margin:14px auto 0;padding:0 20px;display:flex;align-items:center;gap:10px;font-size:11.5px;color:#8b909c}
                .legend .grad{width:180px;height:6px;border-radius:3px;background:linear-gradient(90deg,rgba(226,75,74,.06),rgba(226,75,74,.66))}
                .legend .hint{margin-left:auto;color:#5f6674}
                .code{max-width:1180px;margin:10px auto 60px;padding:6px 0;border:1px solid rgba(255,255,255,.06);border-radius:10px;overflow-x:auto;background:#12141a}
                .ln{display:flex;align-items:stretch;min-height:21px}
                .ln:hover .cd{box-shadow:inset 0 0 0 1px rgba(255,255,255,.14)}
                .gut{width:3px;flex:0 0 3px}
                .no{flex:0 0 54px;text-align:right;padding:0 12px 0 4px;color:#4b5160;font:12px/21px ui-monospace,Consolas,monospace;user-select:none}
                .cd{flex:1 1 auto;padding:0 16px 0 4px;font:12.5px/21px ui-monospace,Consolas,"Courier New",monospace;white-space:pre;tab-size:4}
                .kw{color:#8ab4f8}.cm{color:#6b7280;font-style:italic}.st{color:#9ece6a}.nu{color:#f0a868}.an{color:#e0af68}
                """;
    }

    private static String indexCss() {
        return """
                .wrap{max-width:1180px;margin:0 auto;padding:34px 20px 80px}
                h1{font-size:15px;font-weight:500;margin:0 0 6px;color:#e8eaf0}
                .sub{font-size:12.5px;color:#8b909c;margin:0 0 26px}
                .hero{font-size:46px;font-weight:500;letter-spacing:-1.5px;line-height:1.1;margin:0}
                .cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(158px,1fr));gap:12px;margin:26px 0 34px}
                .card{background:#161922;border:1px solid rgba(255,255,255,.07);border-radius:10px;padding:14px 16px}
                .card .k{font-size:11.5px;color:#8b909c}
                .card .v{font-size:20px;font-weight:500;margin-top:3px;color:#e8eaf0}
                h2{font-size:13px;font-weight:500;color:#c3c8d2;margin:30px 0 8px}
                .grp{margin:0 0 6px}
                .grp h3{font-size:11.5px;font-weight:400;color:#6f7683;margin:14px 0 4px;font-family:ui-monospace,Consolas,monospace}
                .row{display:flex;align-items:center;gap:12px;padding:6px 10px;border-radius:7px;text-decoration:none!important;max-width:860px}
                .row:hover{background:#171b23}
                .row .nm{flex:1 1 auto;font-size:12.5px;color:#cfd4de;font-family:ui-monospace,Consolas,monospace;word-break:break-all}
                .row .met{font-size:11.5px;color:#8b909c;white-space:nowrap}
                .bar2{flex:0 0 120px;height:5px;background:rgba(255,255,255,.06);border-radius:3px;overflow:hidden}
                .bar2 i{display:block;height:100%;background:linear-gradient(90deg,rgba(226,75,74,.5),rgba(226,75,74,.95));border-radius:3px}
                .note{background:#141821;border:1px solid rgba(255,255,255,.07);border-radius:10px;padding:14px 18px;margin-top:32px;font-size:12px;color:#9aa0ab;line-height:1.95}
                .note b{color:#c3c8d2;font-weight:500}
                .empty{color:#8b909c;padding:30px;text-align:center;border:1px dashed rgba(255,255,255,.1);border-radius:10px}
                """;
    }
}
