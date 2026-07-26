package com.aresstack.askai.research.plugin;

import com.aresstack.askai.plugin.api.WorkspacePluginDescriptor;

/** Central definition of the research plugin's stable descriptor. */
public final class ResearchPluginDescriptor {

    public static final String PLUGIN_ID = "com.aresstack.askai.research";

    private ResearchPluginDescriptor() {
    }

    public static WorkspacePluginDescriptor create() {
        return WorkspacePluginDescriptor.builder()
                .id(PLUGIN_ID)
                .displayName("Research Agent")
                .description("Research workspace clickdummy")
                .version("0.1.0")
                .pluginApiVersion(1)
                .provider("AresStack")
                .displayOrder(10)
                .build();
    }
}
