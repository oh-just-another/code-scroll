package dev.codescroll;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

/** Covers editors restored before the editor-factory listener saw them. Never recreates existing maps. */
public final class CodeScrollStartup implements StartupActivity.DumbAware {
    @Override public void runActivity(@NotNull Project project) {
        CodeScrollManager.getInstance().attachExisting();
    }
}
