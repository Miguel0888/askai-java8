package com.aresstack.askai.plugin.api.service;

import javax.swing.JComponent;

/**
 * A host-provided Markdown/Mermaid view. The AskAI implementation wraps the existing MarkdownMessageView so
 * the fixed Markdown/Mermaid behaviour is reused rather than re-implemented in the plugin.
 *
 * <p>Ownership &amp; threading: the {@link MarkdownViewFactory} creates it, the workspace owns it and must
 * call {@link #dispose()} exactly once when done. Every method must be called on the EDT. After
 * {@code dispose()} the view accepts no further updates.</p>
 */
public interface MarkdownView {

    JComponent getComponent();

    void setMarkdown(String markdown);

    void startStreaming();

    void appendMarkdownDelta(String delta);

    void finishStreaming();

    void dispose();
}
