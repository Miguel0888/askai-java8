package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.agent.AgentArtifact;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactContent;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContext;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactWriteResult;
import com.aresstack.askai.plugin.api.service.UiExecutor;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.FlowLayout;

/**
 * The host's default view for Markdown artifacts: a plain source editor plus Save/Reload, reading and writing
 * through the generic {@link AgentArtifactStore} with optimistic locking. Plugins do not ship a Swing class
 * for Markdown documents; only structured artifacts (sources, state) get their own views. Content lives in the
 * store, never in long-lived UI state — it is (re)loaded on demand and the revision guards against lost updates.
 */
final class HostMarkdownArtifactView extends JPanel implements AutoCloseable {

    private final AgentArtifactStore store;
    private final String artifactId;
    private final AgentSession session;
    private final UiExecutor uiExecutor;
    private final JTextArea editor = new JTextArea();
    private final JLabel status = new JLabel(" ");
    private final Runnable sessionChangeListener;
    private long loadedRevision;
    private boolean closed;

    HostMarkdownArtifactView(ArtifactViewContext context) {
        super(new BorderLayout(4, 4));
        AgentArtifact artifact = context.getArtifact();
        this.store = context.getArtifactStore();
        this.artifactId = artifact.getId();
        this.session = context.getSession();
        this.uiExecutor = context.getUiExecutor();
        this.sessionChangeListener = new Runnable() {
            public void run() {
                reloadFromSessionChange();
            }
        };
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        editor.setLineWrap(true);
        editor.setWrapStyleWord(true);
        add(new JScrollPane(editor), BorderLayout.CENTER);

        JButton save = new JButton("Save");
        JButton reload = new JButton("Reload");
        save.addActionListener(e -> save());
        reload.addActionListener(e -> load());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        actions.add(save);
        actions.add(reload);
        actions.add(status);
        add(actions, BorderLayout.SOUTH);

        load();
        if (session != null) {
            session.addStateListener(sessionChangeListener);
        }
    }

    private void load() {
        if (closed) {
            return;
        }
        if (store == null) {
            editor.setEnabled(false);
            status.setText("No artifact store available.");
            return;
        }
        ArtifactContent content = store.read(artifactId);
        editor.setText(content.getMarkdown());
        editor.setCaretPosition(0);
        loadedRevision = content.getRevision();
        status.setText("Loaded (rev " + loadedRevision + ").");
    }

    private void reloadFromSessionChange() {
        if (uiExecutor != null && !uiExecutor.isUiThread()) {
            uiExecutor.execute(new Runnable() {
                public void run() {
                    loadIfChanged();
                }
            });
        } else {
            loadIfChanged();
        }
    }

    private void loadIfChanged() {
        if (closed || store == null) {
            return;
        }
        ArtifactContent content = store.read(artifactId);
        if (content.getRevision() == loadedRevision) {
            return;
        }
        editor.setText(content.getMarkdown());
        editor.setCaretPosition(0);
        loadedRevision = content.getRevision();
        status.setText("Updated (rev " + loadedRevision + ").");
    }

    private void save() {
        if (store == null) {
            return;
        }
        ArtifactWriteResult result = store.replace(artifactId, loadedRevision, editor.getText());
        if (result.isSuccess()) {
            loadedRevision = result.getRevision();
            status.setText("Saved (rev " + loadedRevision + ").");
        } else if (result.getRevision() >= 0) {
            // Someone wrote first: keep the user's text but surface the conflict; reload to rebase.
            status.setText("Not saved: " + result.getReason() + " Reloaded rev " + result.getRevision() + ".");
            editor.setText(result.getCurrentMarkdown());
            loadedRevision = result.getRevision();
        } else {
            status.setText("Not saved: " + result.getReason());
        }
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (session != null) {
            session.removeStateListener(sessionChangeListener);
        }
    }
}
