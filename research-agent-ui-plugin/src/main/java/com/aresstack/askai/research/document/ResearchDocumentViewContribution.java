package com.aresstack.askai.research.document;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactContent;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContext;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactWriteResult;
import com.aresstack.askai.research.agent.ResearchAgentSession;
import com.aresstack.askai.research.agent.ResearchArtifacts;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceQuery;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.util.List;

/**
 * #43 slice 9 — the Document tab: the {@link DocumentPageView} page canvas is the default
 * face (chapter hierarchy, flowing text, clickable {@code [n]} source badges); the plain
 * editor stays one toggle away with the SAME optimistic-locking save semantics the host
 * markdown view used (conflict → reload, never overwrite).
 *
 * <p>SPIKE placeholder, deliberately visible: until #41 introduces the real citation
 * registry, {@code [n]} resolves POSITIONALLY to the n-th source of the repository (documented
 * in the tooltip contract; #41 replaces exactly this resolver). Clicking a badge reveals the
 * Sources tab and focuses that source.</p>
 */
public final class ResearchDocumentViewContribution implements ArtifactViewContribution {

    @Override
    public String getArtifactTypeId() {
        return ResearchArtifacts.TYPE_DOCUMENT;
    }

    @Override
    public String getDisplayName() {
        return "Document";
    }

    @Override
    public JComponent createView(ArtifactViewContext context) {
        final AgentArtifactStore store = context.getArtifactStore();
        final String artifactId = context.getArtifact().getId();
        final DocumentPageView page = new DocumentPageView();
        final JTextArea editor = new JTextArea();
        editor.setLineWrap(true);
        editor.setWrapStyleWord(true);
        final JLabel status = new JLabel(" ");
        final long[] loadedRevision = {0L};

        final JPanel center = new JPanel(new CardLayout());
        JScrollPane pageScroll = new JScrollPane(page);
        pageScroll.setBorder(BorderFactory.createEmptyBorder());
        pageScroll.getViewport().setOpaque(false);
        pageScroll.setOpaque(false);
        center.setOpaque(false);
        center.add(pageScroll, "page");
        center.add(new JScrollPane(editor), "edit");

        final boolean[] editing = {false};
        final JButton toggle = new JButton("Edit");
        final JButton save = new JButton("Save");
        final JButton reload = new JButton("Reload");

        final Runnable load = new Runnable() {
            public void run() {
                if (store == null) {
                    editor.setEnabled(false);
                    status.setText("No artifact store available.");
                    return;
                }
                ArtifactContent content = store.read(artifactId);
                editor.setText(content.getMarkdown());
                editor.setCaretPosition(0);
                loadedRevision[0] = content.getRevision();
                page.setDocumentMarkdown(content.getMarkdown());
                status.setText("Loaded (rev " + loadedRevision[0] + ").");
            }
        };
        final Runnable showCard = new Runnable() {
            public void run() {
                ((CardLayout) center.getLayout()).show(center,
                        editing[0] ? "edit" : "page");
                toggle.setText(editing[0] ? "Page" : "Edit");
                save.setVisible(editing[0]);
                reload.setVisible(editing[0]);
            }
        };
        toggle.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                editing[0] = !editing[0];
                if (!editing[0]) {
                    // Leaving the editor: the page shows what is PERSISTED, so unsaved text
                    // never masquerades as the document — reload keeps both honest.
                    load.run();
                }
                showCard.run();
            }
        });
        save.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (store == null) {
                    return;
                }
                ArtifactWriteResult result =
                        store.replace(artifactId, loadedRevision[0], editor.getText());
                if (result.isSuccess()) {
                    loadedRevision[0] = result.getRevision();
                    page.setDocumentMarkdown(editor.getText());
                    status.setText("Saved (rev " + loadedRevision[0] + ").");
                } else if (result.getRevision() >= 0) {
                    status.setText("Not saved: " + result.getReason() + " Reloaded rev "
                            + result.getRevision() + ".");
                    editor.setText(result.getCurrentMarkdown());
                    loadedRevision[0] = result.getRevision();
                } else {
                    status.setText("Not saved: " + result.getReason());
                }
            }
        });
        reload.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                load.run();
            }
        });

        AgentSession session = context.getSession();
        if (session instanceof ResearchAgentSession) {
            final ResearchAgentSession research = (ResearchAgentSession) session;
            page.setReferenceResolver(new DocumentPageView.ReferenceResolver() {
                public String describeReference(int number) {
                    ResearchSourceRecord record = nthSource(research, number);
                    return record == null ? null : record.getTitle();
                }
            });
            page.setReferenceListener(new DocumentPageView.ReferenceListener() {
                public void referenceClicked(int number) {
                    ResearchSourceRecord record = nthSource(research, number);
                    if (record != null) {
                        research.revealSourceInSourcesTab(record.getSourceId());
                    }
                }
            });
            research.addStateListener(new Runnable() {
                public void run() {
                    if (!editing[0]) {
                        load.run(); // the agent wrote: the page face follows the store
                    }
                }
            });
        }

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        toolbar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        toolbar.add(toggle);
        toolbar.add(save);
        toolbar.add(reload);
        toolbar.add(status);

        JPanel root = new JPanel(new BorderLayout(4, 4));
        root.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        root.add(toolbar, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        load.run();
        showCard.run();
        return root;
    }

    /** SPIKE resolver: [n] = the n-th repository source (1-based) until #41's registry. */
    private static ResearchSourceRecord nthSource(ResearchAgentSession research, int number) {
        List<ResearchSourceRecord> records =
                research.getSourceRepository().find(SourceQuery.all());
        return number >= 1 && number <= records.size() ? records.get(number - 1) : null;
    }
}
