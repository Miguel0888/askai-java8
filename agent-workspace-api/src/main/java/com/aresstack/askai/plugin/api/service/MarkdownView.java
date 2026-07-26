package com.aresstack.askai.plugin.api.service;

import javax.swing.JComponent;

/**
 * A host-provided Markdown/Mermaid view. The AskAI implementation wraps the existing MarkdownMessageView so
 * the fixed Markdown/Mermaid behaviour is reused rather than re-implemented in the plugin.
 */
public interface MarkdownView {

    JComponent getComponent();

    void setMarkdown(String markdown);

    void startStreaming();

    void appendMarkdownDelta(String delta);

    void finishStreaming();

    void dispose();
}
