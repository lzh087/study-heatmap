package com.study.heatmap.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.util.Consumer;
import com.study.heatmap.actions.GenerateReportAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Component;
import java.awt.event.MouseEvent;

/** 状态栏上的实时读数，点一下就能生成报告。 */
public class StudyStatusBarWidget implements StatusBarWidget, StatusBarWidget.TextPresentation {

    public static final String WIDGET_ID = "StudyHeatmap";

    private final Project project;
    private StatusBar statusBar;
    private String text = "学习 00:00";
    private String tooltip = "学习计时中";
    private volatile boolean disposed;

    public StudyStatusBarWidget(Project project) {
        this.project = project;
    }

    @Override
    public @NotNull String ID() {
        return WIDGET_ID;
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        this.statusBar = statusBar;
    }

    @Override
    public void dispose() {
        disposed = true;
        statusBar = null;
    }

    @Override
    public @Nullable WidgetPresentation getPresentation() {
        return this;
    }

    @Override
    public @NotNull String getText() {
        return text;
    }

    @Override
    public float getAlignment() {
        return Component.CENTER_ALIGNMENT;
    }

    @Override
    public @Nullable String getTooltipText() {
        return tooltip;
    }

    @Override
    public @Nullable Consumer<MouseEvent> getClickConsumer() {
        return e -> GenerateReportAction.generate(project);
    }

    public void update(String newText, String newTooltip) {
        if (disposed) {
            return;
        }
        this.text = newText;
        this.tooltip = newTooltip;
        StatusBar sb = statusBar;
        if (sb != null) {
            sb.updateWidget(WIDGET_ID);
        }
    }
}
