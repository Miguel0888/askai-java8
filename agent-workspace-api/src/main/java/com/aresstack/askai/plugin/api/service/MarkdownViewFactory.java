package com.aresstack.askai.plugin.api.service;

/** Creates {@link MarkdownView}s backed by the host's Markdown/Mermaid renderer. */
public interface MarkdownViewFactory {

    MarkdownView create(MarkdownViewOptions options);
}
