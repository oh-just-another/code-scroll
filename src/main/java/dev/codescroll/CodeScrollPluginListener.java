package dev.codescroll;

import com.intellij.ide.plugins.DynamicPluginListener;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import org.jetbrains.annotations.NotNull;

/** Startup activities do not run for already open projects when the plugin is installed without restart. */
public final class CodeScrollPluginListener implements DynamicPluginListener {
    @Override public void pluginLoaded(@NotNull IdeaPluginDescriptor descriptor) {
        if ("dev.codescroll".equals(descriptor.getPluginId().getIdString())) {
            CodeScrollManager.getInstance().attachExisting();
        }
    }
}
