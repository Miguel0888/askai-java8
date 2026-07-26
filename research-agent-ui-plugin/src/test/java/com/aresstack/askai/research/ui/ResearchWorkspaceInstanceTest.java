package com.aresstack.askai.research.ui;

import com.aresstack.askai.plugin.api.WorkspaceCreationRequest;
import com.aresstack.askai.plugin.api.service.ConversationSurface;
import com.aresstack.askai.plugin.api.service.ConversationSurfaceFactory;
import com.aresstack.askai.plugin.api.service.ConversationSurfaceOptions;
import com.aresstack.askai.plugin.api.service.InteractionModeControls;
import com.aresstack.askai.plugin.api.service.InteractionModeControlsFactory;
import com.aresstack.askai.plugin.api.service.InteractionModeControlsOptions;
import com.aresstack.askai.plugin.api.service.MarkdownView;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.MarkdownViewOptions;
import com.aresstack.askai.plugin.api.service.NotificationService;
import com.aresstack.askai.plugin.api.service.PluginPathService;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.api.service.WorkspaceHostContext;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;

import org.junit.Test;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/** The research workspace builds all five host-backed slots per instance and disposes cleanly. */
public class ResearchWorkspaceInstanceTest {

    private static ResearchWorkspaceInstance open(FakeHost host, String id) {
        return new ResearchWorkspaceInstance(
                new WorkspaceCreationRequest(id, "", Collections.<String, String>emptyMap()), host);
    }

    @Test
    public void buildsAllFiveSlotsFromHostServices() throws Exception {
        final FakeHost host = new FakeHost();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                ResearchWorkspaceInstance ws = open(host, "w1");
                assertTrue(ws.getLayout().getToolbar().isPresent());
                assertTrue(ws.getLayout().getNavigation().isPresent());
                assertTrue(ws.getLayout().getActivity().isPresent());
                assertTrue(ws.getLayout().getComposer().isPresent());
                assertTrue(ws.getLayout().getMainContent() != null);
                // Markdown, conversation and mode controls all came from the host services.
                assertEquals(1, host.markdownCreated);
                assertEquals(1, host.conversationCreated);
                assertEquals(1, host.controlsCreated);
            }
        });
    }

    @Test
    public void twoInstancesDoNotShareComponents() throws Exception {
        final FakeHost host = new FakeHost();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                ResearchWorkspaceInstance a = open(host, "wa");
                ResearchWorkspaceInstance b = open(host, "wb");
                assertNotSame(a.getLayout().getToolbar().get(), b.getLayout().getToolbar().get());
                assertNotSame(a.getLayout().getMainContent(), b.getLayout().getMainContent());
            }
        });
    }

    @Test
    public void disposeIsIdempotentAndReleasesHostViewsAndThemeListener() throws Exception {
        final FakeHost host = new FakeHost();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                ResearchWorkspaceInstance ws = open(host, "w1");
                ws.activate();
                assertEquals(1, host.theme.listeners);
                ws.dispose();
                ws.dispose(); // idempotent
                assertTrue(host.lastMarkdown.disposed);
                assertTrue(host.lastConversation.disposed);
                assertTrue(host.lastControls.disposed);
                assertEquals(0, host.theme.listeners);
            }
        });
    }

    @Test
    public void dividerWidthsAreRestoredFromStateStore() throws Exception {
        final FakeHost host = new FakeHost();
        host.state.putInt("research.navWidth", 999);
        host.state.putInt("research.activityWidth", 111);
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                ResearchWorkspaceInstance ws = open(host, "w1");
                assertEquals(999, ws.getLayout().getLayoutHints().getPreferredNavigationWidth());
                assertEquals(111, ws.getLayout().getLayoutHints().getPreferredActivityWidth());
            }
        });
    }

    @Test
    public void survivesAThemeChangeAndHeadlessPaint() throws Exception {
        final FakeHost host = new FakeHost();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                ResearchWorkspaceInstance ws = open(host, "w1");
                ws.activate();
                host.theme.dark = true;
                host.theme.fire(); // theme change must not throw
                JComponent main = ws.getLayout().getMainContent();
                main.setSize(900, 500);
                main.doLayout();
                java.awt.image.BufferedImage image =
                        new java.awt.image.BufferedImage(900, 500, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = image.createGraphics();
                try {
                    main.paint(g);
                } finally {
                    g.dispose();
                }
            }
        });
    }

    // ------------------------------------------------------------------ fakes

    private static final class FakeHost implements WorkspaceHostContext {
        final FakeTheme theme = new FakeTheme();
        final MapStore state = new MapStore();
        int markdownCreated;
        int conversationCreated;
        int controlsCreated;
        FakeMarkdown lastMarkdown;
        FakeConversation lastConversation;
        FakeControls lastControls;

        public UiExecutor getUiExecutor() {
            return new UiExecutor() {
                public boolean isUiThread() {
                    return true;
                }

                public void execute(Runnable runnable) {
                    runnable.run();
                }

                public void assertUiThread() {
                }
            };
        }

        public ThemeService getThemeService() {
            return theme;
        }

        public MarkdownViewFactory getMarkdownViewFactory() {
            return new MarkdownViewFactory() {
                public MarkdownView create(MarkdownViewOptions options) {
                    markdownCreated++;
                    lastMarkdown = new FakeMarkdown();
                    return lastMarkdown;
                }
            };
        }

        public ConversationSurfaceFactory getConversationSurfaceFactory() {
            return new ConversationSurfaceFactory() {
                public ConversationSurface create(ConversationSurfaceOptions options) {
                    conversationCreated++;
                    lastConversation = new FakeConversation();
                    return lastConversation;
                }
            };
        }

        public InteractionModeControlsFactory getInteractionModeControlsFactory() {
            return new InteractionModeControlsFactory() {
                public InteractionModeControls create(InteractionModeControlsOptions options) {
                    controlsCreated++;
                    lastControls = new FakeControls();
                    return lastControls;
                }
            };
        }

        public WorkspaceStateStore getWorkspaceStateStore() {
            return state;
        }

        public PluginPathService getPluginPathService() {
            return new PluginPathService() {
                public File getPluginDataDirectory() {
                    return new File(System.getProperty("java.io.tmpdir"));
                }

                public File getWorkspaceDirectory(String workspaceInstanceId) {
                    return new File(System.getProperty("java.io.tmpdir"));
                }
            };
        }

        public NotificationService getNotificationService() {
            return new NotificationService() {
                public void notify(Severity severity, String message) {
                }
            };
        }
    }

    private static final class FakeTheme implements ThemeService {
        int listeners;
        boolean dark;
        private final java.util.List<Runnable> registered = new java.util.ArrayList<Runnable>();

        public Color color(String key, Color fallback) {
            return fallback;
        }

        public boolean isDark() {
            return dark;
        }

        public void addThemeChangeListener(Runnable listener) {
            registered.add(listener);
            listeners = registered.size();
        }

        public void removeThemeChangeListener(Runnable listener) {
            registered.remove(listener);
            listeners = registered.size();
        }

        void fire() {
            for (Runnable r : registered) {
                r.run();
            }
        }
    }

    private static final class FakeMarkdown implements MarkdownView {
        boolean disposed;
        private final JPanel component = new JPanel();

        public JComponent getComponent() {
            return component;
        }

        public void setMarkdown(String markdown) {
        }

        public void startStreaming() {
        }

        public void appendMarkdownDelta(String delta) {
        }

        public void finishStreaming() {
        }

        public void dispose() {
            disposed = true;
        }
    }

    private static final class FakeControls implements InteractionModeControls {
        boolean disposed;
        private final JPanel component = new JPanel();

        public JComponent getComponent() {
            return component;
        }

        public void dispose() {
            disposed = true;
        }
    }

    private static final class FakeConversation implements ConversationSurface {
        boolean disposed;
        private final JPanel component = new JPanel();

        public JComponent getComponent() {
            return component;
        }

        public void addUserMessage(String messageId, String markdown) {
        }

        public void addAssistantMessage(String messageId, String markdown) {
        }

        public void startAssistantStreaming(String messageId) {
        }

        public void appendAssistantDelta(String messageId, String delta) {
        }

        public void finishAssistantStreaming(String messageId) {
        }

        public void startThinking(String activityId, String title) {
        }

        public void updateThinking(String activityId, String text) {
        }

        public void finishThinking(String activityId, String summary) {
        }

        public void startToolActivity(String activityId, String title, String explanation) {
        }

        public void updateToolActivity(String activityId, String title, String explanation) {
        }

        public void markApprovalRequired(String activityId, String explanation) {
        }

        public void completeToolActivity(String activityId, String summary) {
        }

        public void failToolActivity(String activityId, String summary) {
        }

        public void cancelActivity(String activityId, String summary) {
        }

        public void clear() {
        }

        public void dispose() {
            disposed = true;
        }
    }

    private static final class MapStore implements WorkspaceStateStore {
        private final Map<String, String> values = new HashMap<String, String>();

        public String get(String key, String defaultValue) {
            String v = values.get(key);
            return v == null ? defaultValue : v;
        }

        public boolean getBoolean(String key, boolean defaultValue) {
            String v = values.get(key);
            return v == null ? defaultValue : Boolean.parseBoolean(v);
        }

        public int getInt(String key, int defaultValue) {
            String v = values.get(key);
            try {
                return v == null ? defaultValue : Integer.parseInt(v);
            } catch (NumberFormatException ex) {
                return defaultValue;
            }
        }

        public void put(String key, String value) {
            values.put(key, value);
        }

        public void putBoolean(String key, boolean value) {
            values.put(key, Boolean.toString(value));
        }

        public void putInt(String key, int value) {
            values.put(key, Integer.toString(value));
        }
    }
}
