package com.study.heatmap.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import com.study.heatmap.tracker.StudyTrackerService;
import org.jetbrains.annotations.NotNull;

public class StudyStatusBarWidgetFactory implements StatusBarWidgetFactory {

    @Override
    public @NotNull String getId() {
        return StudyStatusBarWidget.WIDGET_ID;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "学习计时";
    }

    @Override
    public boolean isAvailable(@NotNull Project project) {
        return !project.isDefault();
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        StudyStatusBarWidget widget = new StudyStatusBarWidget(project);
        StudyTrackerService service = StudyTrackerService.getInstance(project);
        service.attachWidget(widget);
        service.start();
        return widget;
    }

    @Override
    public void disposeWidget(@NotNull StatusBarWidget widget) {
        widget.dispose();
    }

    @Override
    public boolean canBeEnabledOn(@NotNull StatusBar statusBar) {
        return true;
    }
}
