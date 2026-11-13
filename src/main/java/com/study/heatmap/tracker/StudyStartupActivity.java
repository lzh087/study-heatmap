package com.study.heatmap.tracker;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** 项目打开后立刻开始计时，不依赖状态栏是否可见。 */
public final class StudyStartupActivity implements ProjectActivity {

    @Override
    public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        if (!project.isDefault()) {
            StudyTrackerService.getInstance(project).start();
        }
        return Unit.INSTANCE;
    }
}
