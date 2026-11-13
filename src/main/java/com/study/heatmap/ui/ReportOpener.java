package com.study.heatmap.ui;

import com.intellij.ide.BrowserUtil;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.study.heatmap.core.Heat;
import com.study.heatmap.core.PdfExporter;
import com.study.heatmap.core.ReportRenderer;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 打开/交付报告。
 *
 * 这台机器上 Windows 的 ShellExecute 是坏的：`.html` 的 ProgId 明明指向 msedge.exe，
 * 但 Desktop.open 和 file:// URL 都会返回「找不到应用程序」。所以这里不再依赖文件关联，
 * 优先直接启动浏览器可执行文件，把路径作为参数传进去。
 */
public final class ReportOpener {

    private static final String PDF_NAME = "learning-heatmap.pdf";

    private ReportOpener() {
    }

    /**
     * 把打印稿转成 PDF，并清掉临时 HTML。返回 null 表示这台机器上没找到浏览器。
     */
    public static Path exportPdf(ReportRenderer.Result result) {
        Path pdf = result.index().resolveSibling(PDF_NAME);
        Path out = PdfExporter.export(result.printHtml(), pdf);
        try {
            Files.deleteIfExists(result.printHtml());
        } catch (IOException ignored) {
            // ignore
        }
        return out;
    }

    public static void open(Path file) {
        if (file == null) {
            return;
        }
        Path browser = PdfExporter.findBrowser();
        if (browser != null) {
            try {
                new ProcessBuilder(browser.toString(), file.toAbsolutePath().toString()).start();
                return;
            } catch (Throwable ignored) {
                // 落到下面的兜底
            }
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file.toFile());
                return;
            }
        } catch (Throwable ignored) {
            // 落到下面的兜底
        }
        try {
            BrowserUtil.browse(file.toUri().toString());
        } catch (Throwable ignored) {
            // 通知里还有「打开所在文件夹」
        }
    }

    public static void revealInExplorer(Path file) {
        try {
            new ProcessBuilder("explorer.exe", "/select," + file.toAbsolutePath()).start();
            return;
        } catch (Throwable ignored) {
            // 落到下面的兜底
        }
        File dir = file.getParent() == null ? file.toFile() : file.getParent().toFile();
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(dir);
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    public static void notifyReady(Project project, ReportRenderer.Result result, Path pdf) {
        NotificationGroup group = group();
        if (group == null) {
            return;
        }
        Path target = pdf != null ? pdf : result.index();
        String body = "累计 " + Heat.duration(result.totalSeconds())
                + " · " + result.fileCount() + " 个文件"
                + " · 覆盖 " + result.coveredLines() + "/" + result.totalLines() + " 行\n"
                + target;
        Notification notification = group.createNotification("学习热力图已生成", body, NotificationType.INFORMATION);
        notification.addAction(NotificationAction.createSimple("打开报告", () -> open(target)));
        notification.addAction(NotificationAction.createSimple("网页版", () -> open(result.index())));
        notification.addAction(NotificationAction.createSimple("所在文件夹", () -> revealInExplorer(target)));
        notification.notify(project);
    }

    private static NotificationGroup group() {
        try {
            return NotificationGroupManager.getInstance().getNotificationGroup("StudyHeatmap");
        } catch (Throwable t) {
            return null;
        }
    }
}
