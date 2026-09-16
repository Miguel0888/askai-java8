package com.aresstack.askai.research.host;

import com.aresstack.askai.mcp.api.InProcessMcpServerRegistry;
import com.aresstack.askai.plugin.api.agent.AgentConversationSink;
import com.aresstack.askai.plugin.api.agent.AgentHostContext;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.NotificationService;
import com.aresstack.askai.plugin.api.service.PluginPathService;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.agent.ResearchAgentSession;
import com.aresstack.askai.research.agent.ResearchArtifactStore;
import com.aresstack.askai.research.backend.ResearchCommandDispatchResult;
import com.aresstack.askai.research.backend.ResearchProjectRequest;
import com.aresstack.askai.research.backend.ResearchPrompt;
import com.aresstack.askai.research.backend.ResearchSessionBackend;
import com.aresstack.askai.research.backend.ResearchSessionHandle;
import com.aresstack.askai.research.backend.ResearchSessionListener;
import com.aresstack.askai.research.capture.CaptureStore;
import com.aresstack.askai.research.capture.ImportedSourceStore;
import com.aresstack.askai.research.capture.ResearchSearchIndex;
import com.aresstack.askai.research.capture.SourceAcceptanceService;
import com.aresstack.askai.research.capture.SourceImportService;
import com.aresstack.askai.research.mcp.ResearchControlContext;
import com.aresstack.askai.research.mcp.ResearchControlEndpoint;
import com.aresstack.askai.research.sources.InMemoryResearchSourceRepository;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.ResearchSourceRepository;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.oo.OoResearchStateMachine;
import com.aresstack.askai.research.state.oo.ResearchStateIds;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * #39 off-EDT pin: a user import submitted from a background worker keeps the WHOLE import
 * (extraction + acceptance) on that worker, but the state notification that makes Swing
 * views refresh runs EXCLUSIVELY through the session's UiExecutor — never directly on the
 * import worker (fireStateChanged executes its listeners on the calling thread, and the
 * sources-view listener touches Swing).
 */
public class SourceImportThreadingTest {

    /** Marks every runnable it executes, so a listener can prove it ran inside the executor. */
    private static final class MarkingUi implements UiExecutor {
        final ThreadLocal<Boolean> inside = new ThreadLocal<Boolean>();
        final AtomicInteger executions = new AtomicInteger();

        public void execute(Runnable runnable) {
            executions.incrementAndGet();
            inside.set(Boolean.TRUE);
            try {
                runnable.run();
            } finally {
                inside.set(Boolean.FALSE);
            }
        }

        public void assertUiThread() {
        }

        public boolean isUiThread() {
            return true;
        }

        boolean isInside() {
            return Boolean.TRUE.equals(inside.get());
        }
    }

    private static final class QuietBackend implements ResearchSessionBackend {
        public ResearchSessionHandle createSession(ResearchProjectRequest request,
                                                   ResearchSessionListener listener) {
            final String sessionId = request.getSessionId();
            final String projectId = request.getProjectId();
            return new ResearchSessionHandle() {
                public String getSessionId() {
                    return sessionId;
                }

                public String getProjectId() {
                    return projectId;
                }
            };
        }

        public boolean canExecute(ResearchSessionHandle handle, ResearchCommandType command) {
            return false;
        }

        public void executeCommand(ResearchSessionHandle handle, ResearchCommandType command) {
        }

        public void submitPrompt(ResearchSessionHandle handle, ResearchPrompt prompt) {
        }

        public void approve(ResearchSessionHandle handle, String approvalId) {
        }

        public void reject(ResearchSessionHandle handle, String approvalId, String reason) {
        }

        public void pause(ResearchSessionHandle handle) {
        }

        public void resume(ResearchSessionHandle handle) {
        }

        public void cancel(ResearchSessionHandle handle) {
        }

        public void close(ResearchSessionHandle handle) {
        }
    }

    private static final class MarkedUiHost implements AgentHostContext {
        final MarkingUi ui;

        MarkedUiHost(MarkingUi ui) {
            this.ui = ui;
        }

        public UiExecutor getUiExecutor() {
            return ui;
        }

        public ThemeService getThemeService() {
            return null;
        }

        public MarkdownViewFactory getMarkdownViewFactory() {
            return null;
        }

        public NotificationService getNotificationService() {
            return null;
        }

        public WorkspaceStateStore getStateStore() {
            return null;
        }

        public PluginPathService getPluginPathService() {
            return null;
        }

        public AgentConversationSink getConversationSink() {
            return null;
        }
    }

    private static com.aresstack.askai.research.store.ResearchProjectContext tempProjectContext() {
        try {
            java.io.File dir = java.nio.file.Files.createTempDirectory("askai-research-test")
                    .toFile();
            return com.aresstack.askai.research.store.ResearchProjectContext.open("s1", dir);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    public void aWorkerThreadImportNotifiesStateListenersOnlyThroughTheUiExecutor()
            throws Exception {
        final MarkingUi ui = new MarkingUi();
        final ProductiveResearchSessionResources[] holder =
                new ProductiveResearchSessionResources[1];
        ResearchControlEndpoint control = new ResearchControlEndpoint(
                new InProcessMcpServerRegistry(), "s1", 7L, new ResearchControlContext() {
                    public String currentPhaseId() {
                        return holder[0] == null ? ResearchStateIds.SCOPING
                                : holder[0].currentState().getPhaseId();
                    }

                    public String currentStateId() {
                        return holder[0] == null ? ResearchStateIds.NEW
                                : holder[0].currentState().getStateId();
                    }

                    public String statusLine() {
                        return currentPhaseId() + "/" + currentStateId();
                    }

                    public com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore
                            artifactStore() {
                        return new ResearchArtifactStore();
                    }

                    public ResearchSourceRepository sourceRepository() {
                        return new InMemoryResearchSourceRepository();
                    }

                    public String acceptCapture(String captureId) {
                        return null;
                    }
                });
        control.open();
        ProductiveResearchSessionResources resources = new ProductiveResearchSessionResources(
                "s1", new OoResearchStateMachine("s1"), null, null, null,
                tempProjectContext(), control, null, null, null);
        holder[0] = resources;

        // The REAL import stack (capture → acceptance → raw snapshot), exactly as productive.
        CaptureStore captures = new CaptureStore(20, 1000L);
        final InMemoryResearchSourceRepository repo = InMemoryResearchSourceRepository.empty();
        SourceAcceptanceService acceptance = new SourceAcceptanceService(captures, repo,
                new SourceAcceptanceService.SourceCreator() {
                    public void create(ResearchSourceRecord record) {
                        repo.put(record);
                    }
                }, new ResearchSearchIndex.InMemory());
        resources.setSourceImportService(new SourceImportService(captures, acceptance, repo,
                new ImportedSourceStore(java.nio.file.Files
                        .createTempDirectory("askai-imports").toFile())));

        final ResearchAgentSession session = new ResearchAgentSession(new QuietBackend(), null,
                new MarkedUiHost(ui), "s1", "p1", resources);
        final AtomicInteger workerNotifications = new AtomicInteger();
        final AtomicBoolean escapedTheExecutor = new AtomicBoolean(false);
        session.addStateListener(new Runnable() {
            public void run() {
                if ("import-worker".equals(Thread.currentThread().getName())) {
                    workerNotifications.incrementAndGet();
                    if (!ui.isInside()) {
                        escapedTheExecutor.set(true); // a direct Swing touch on the worker
                    }
                }
            }
        });

        final String[] outcome = new String[1];
        Thread worker = new Thread(new Runnable() {
            public void run() {
                outcome[0] = session.importUserSource(
                        new SourceImportService.SourceInput(SourceImportService.Kind.TEXT,
                                "Notes", "", "Imported from the background worker."));
            }
        }, "import-worker");
        worker.start();
        worker.join(10_000L);

        assertTrue("the import itself succeeded on the worker",
                outcome[0] != null && outcome[0].startsWith("handled"));
        assertTrue("the import DID notify state listeners", workerNotifications.get() > 0);
        assertTrue("the UiExecutor carried the notification", ui.executions.get() > 0);
        assertEquals("no state listener ever ran on the worker OUTSIDE the UiExecutor",
                false, escapedTheExecutor.get());
    }
}
