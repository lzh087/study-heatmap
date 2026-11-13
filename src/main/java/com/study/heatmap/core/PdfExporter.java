package com.study.heatmap.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 把渲染好的 HTML 打印成 PDF。
 *
 * 不引入任何 PDF 库，而是复用系统里必然存在的 Chromium 内核浏览器（Edge 优先）的
 * 无头打印能力。好处是排版和浏览器里看到的完全一致、中文不需要额外字体处理；
 * 代价是依赖一个浏览器可执行文件，找不到就退回只出 HTML。
 */
public final class PdfExporter {

    private static final List<String> CANDIDATES = List.of(
            "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
            "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
            "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
            "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe"
    );

    private PdfExporter() {
    }

    /** 找系统里的 Edge / Chrome。找不到返回 null。 */
    public static Path findBrowser() {
        for (String c : CANDIDATES) {
            Path p = Path.of(c);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            for (String rel : new String[]{
                    "\\Microsoft\\Edge\\Application\\msedge.exe",
                    "\\Google\\Chrome\\Application\\chrome.exe"}) {
                Path p = Path.of(local + rel);
                if (Files.isRegularFile(p)) {
                    return p;
                }
            }
        }
        return null;
    }

    /**
     * 打印成 PDF。成功返回 PDF 路径，失败（没有浏览器、超时、文件没生成）返回 null。
     * 调用方负责在后台线程里跑，这个过程通常 1~5 秒。
     */
    public static Path export(Path htmlFile, Path pdfFile) {
        Path browser = findBrowser();
        if (browser == null || !Files.isRegularFile(htmlFile)) {
            return null;
        }
        try {
            if (pdfFile.getParent() != null) {
                Files.createDirectories(pdfFile.getParent());
            }
            Files.deleteIfExists(pdfFile);
            ProcessBuilder pb = new ProcessBuilder(
                    browser.toString(),
                    "--headless=new",
                    "--disable-gpu",
                    "--no-pdf-header-footer",
                    "--print-to-pdf=" + pdfFile.toAbsolutePath(),
                    htmlFile.toUri().toString());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process process = pb.start();
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (Files.isRegularFile(pdfFile) && Files.size(pdfFile) > 1024) {
                return pdfFile;
            }
        } catch (Exception ignored) {
            // 打印失败不影响 HTML 报告本身
        }
        return null;
    }
}
