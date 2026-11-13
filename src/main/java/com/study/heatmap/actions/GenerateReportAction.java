package com.study.heatmap.actions;

import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.study.heatmap.core.ReportRenderer;
import com.study.heatmap.tracker.StudyTrackerService;
import com.study.heatmap.ui.ReportOpener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Tools 菜单 / 状态栏点击：立刻结算并生成热力图报告。 */
public class GenerateReportAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(GenerateReportAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        generate(e.getProject());
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(e.getProject() != null);
    }

    public static void generate(@Nullable Project project) {
        if (project == null || project.isDisposed()) {
            return;
        }
        StudyTrackerService service = StudyTrackerService.getInstance(project);
        service.start();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                ReportRenderer.Result result = service.generateReport();
                java.nio.file.Path pdf = ReportOpener.exportPdf(result);
                ApplicationManager.getApplication().invokeLater(() -> {
                    ReportOpener.open(pdf != null ? pdf : result.index());
                    ReportOpener.notifyReady(project, result, pdf);
                });
            } catch (Exception ex) {
                LOG.warn("StudyHeatmap: 生成报告失败", ex);
                ApplicationManager.getApplication().invokeLater(() -> {
                    NotificationGroup group = group();
                    if (group == null) {
                        return;
                    }
                    Notifications.Bus.notify(group.createNotification(
                            "学习热力图生成失败", String.valueOf(ex.getMessage()), NotificationType.ERROR), project);
                });
            }
        });
    }

    private static NotificationGroup group() {
        try {
            return NotificationGroupManager.getInstance().getNotificationGroup("StudyHeatmap");
        } catch (Throwable t) {
            return null;
        }
    }
}
