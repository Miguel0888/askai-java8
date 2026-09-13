package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContext;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.plugin.api.service.UiExecutor;

import javax.swing.JComponent;

/**
 * Contributes the "Concept" view for the {@code research.brief} artifact — the scoping phase's
 * PRIMARY artifact and, since the raw-edit slice, the user's DIRECT editor: manual edits go
 * through {@code ConceptBranchService.replaceDocument} (strict parse → envelope check → CAS →
 * pretty-print → atomic commit), history browsing reads the working-revision files. One atomic
 * snapshot per refresh, rendered straight from the session's ConceptBranchService — no second
 * UI model, no JSON in events. Re-renders on every session state change; unsaved user edits are
 * never clobbered (the view keeps them until Save/Discard).
 */
public final class ResearchBriefViewContribution implements ArtifactViewContribution {

    @Override
    public String getArtifactTypeId() {
        return ResearchArtifacts.TYPE_BRIEF;
    }

    @Override
    public String getDisplayName() {
        return "Concept";
    }

    @Override
    public JComponent createView(ArtifactViewContext context) {
        final ConceptPaperView view = new ConceptPaperView();
        AgentSession session = context.getSession();
        if (!(session instanceof ResearchAgentSession)) {
            return view;
        }
        final ResearchAgentSession research = (ResearchAgentSession) session;
        final UiExecutor uiExecutor = context.getUiExecutor();
        // The user's manual edit + rollback path — the SAME validating pipeline as every agent
        // write (strict parse, envelope check, CAS against the shown revision, atomic commit).
        view.setEditActions(new ConceptPaperView.SaveHandler() {
            public String save(String documentJson, long expectedRevision) {
                com.aresstack.askai.research.concept.ConceptBranchService service =
                        research.conceptBranchService();
                if (service == null) {
                    return "This session has no concept service.";
                }
                com.aresstack.askai.research.concept.ConceptBranchService.EditResult result =
                        service.replaceDocument(documentJson, expectedRevision);
                return result.isApplied() ? null : result.getDiagnostic().describeForModel();
            }
        }, new ConceptPaperView.HistoryReader() {
            public String content(long revision) {
                com.aresstack.askai.research.concept.ConceptBranchService service =
                        research.conceptBranchService();
                return service == null ? null : service.workingHistoryContent(revision);
            }
        });
        final Runnable refresh = new Runnable() {
            public void run() {
                // ONE atomic snapshot per refresh (JSON + revision from the same state); the
                // store stays the only truth — no event ever carries the JSON. K4: the legacy
                // brief is no longer read — the concept IS the scoping artifact (persisted old
                // briefs stay on disk untouched, they just are not a view anymore).
                com.aresstack.askai.research.concept.ConceptBranchService service =
                        research.conceptBranchService();
                final com.aresstack.askai.research.concept.ConceptProjection projection =
                        service == null ? null
                                : com.aresstack.askai.research.concept.ConceptProjection
                                        .of(service.snapshot());
                uiExecutor.execute(new Runnable() {
                    public void run() {
                        view.render(projection);
                    }
                });
            }
        };
        // The manual ⟳ runs exactly the same re-read as every listener — never a second path.
        view.setRefreshAction(refresh);
        view.addAncestorListener(new javax.swing.event.AncestorListener() {
            public void ancestorAdded(javax.swing.event.AncestorEvent event) {
                // Tab (re)shown: RE-ATTACH the observers AND RE-READ, so content written while
                // the tab was hidden appears immediately. Both adds are addIfAbsent-safe.
                research.addStateListener(refresh);
                attachConceptListener(research, refresh);
                refresh.run();
            }

            public void ancestorMoved(javax.swing.event.AncestorEvent event) {
            }

            public void ancestorRemoved(javax.swing.event.AncestorEvent event) {
                // Tab hidden/closed: stop live updates so the listener sets never grow, but keep
                // the reusable MarkdownViews intact for a later re-show.
                research.removeStateListener(refresh);
                com.aresstack.askai.research.concept.ConceptBranchService service =
                        research.conceptBranchService();
                if (service != null) {
                    service.removeChangeListener(refresh);
                }
            }
        });
        research.addStateListener(refresh);
        // Subscribe DIRECTLY at the service — the single shared truth notifies on every applied
        // edit, with no delegation chain in between (the live gate caught the long chain
        // dropping updates: the JSON view sat on rev 1 while the agent committed rev 3).
        attachConceptListener(research, refresh);
        refresh.run(); // initial paint (shows persisted working copy on restore)
        return view;
    }

    private static void attachConceptListener(ResearchAgentSession research, Runnable refresh) {
        com.aresstack.askai.research.concept.ConceptBranchService service =
                research.conceptBranchService();
        if (service != null) {
            service.addChangeListener(refresh);
        }
    }
}
