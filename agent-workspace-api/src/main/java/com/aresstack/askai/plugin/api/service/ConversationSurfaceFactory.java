package com.aresstack.askai.plugin.api.service;

/** Creates {@link ConversationSurface}s backed by the host's bubble/thinking/tool-activity components. */
public interface ConversationSurfaceFactory {

    ConversationSurface create(ConversationSurfaceOptions options);
}
