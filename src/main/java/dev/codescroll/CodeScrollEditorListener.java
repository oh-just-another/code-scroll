package dev.codescroll;

import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import org.jetbrains.annotations.NotNull;

public final class CodeScrollEditorListener implements EditorFactoryListener {
    @Override public void editorCreated(@NotNull EditorFactoryEvent event) {
        CodeScrollManager.getInstance().attachLater(event.getEditor());
    }
    @Override public void editorReleased(@NotNull EditorFactoryEvent event) {
        CodeScrollManager.getInstance().detach(event.getEditor());
    }
}
