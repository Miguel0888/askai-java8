package com.aresstack.askai.java8.ui.chat;

import com.aresstack.askai.java8.history.ChatHistoryStore;
import com.aresstack.askai.java8.history.ChatRecord;
import com.aresstack.askai.java8.ui.ChatComposerPanel;
import com.aresstack.askai.java8.ui.OllamaChatPanel;
import com.aresstack.askai.java8.ui.sidebar.ChatSidebarPanel;
import com.aresstack.askai.java8.ui.sidebar.ChatSidebarTab;
import com.aresstack.comiccontrols.control.ComicScrollPane;
import com.aresstack.comiccontrols.control.ComicSplitPane;
import com.aresstack.comiccontrols.control.ResearchIconButton;
import com.aresstack.comiccontrols.control.ResearchPillButton;
import com.aresstack.comiccontrols.theme.ResearchUiMetrics;
import com.aresstack.comiccontrols.theme.ResearchUiPainter;
import com.aresstack.comiccontrols.theme.ResearchUiPalette;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hosts independent chat sessions as full-area cards — there is NO tab strip anymore. Switching,
 * opening and closing chats happens through the sidebar (drawer): its "Chats" tab merges the OPEN
 * sessions with the saved history, so one list is both the tab bar replacement and the history. The
 * drawer spans the workspace's full height on the left, opens on hamburger hover/click, closes when
 * the mouse leaves it (unless pinned), and the top bar carries the hamburger (top-left) plus the
 * agent toolbar slots; New chat and the settings gear live INSIDE the drawer's Chats pane (button on
 * top, gear in the footer). Each chat keeps a stable {@link ChatSessionId}; sessions are addressed
 * by id, never by index. At least one chat stays open.
 */
public final class ChatWorkspacePanel extends JPanel {

    /** Builds the per-tab chat session for a freshly created id (kept out of the host for testability). */
    public interface ChatSessionFactory {
        ChatSessionComponent create(ChatSessionId id);
    }

    /** Notified when the active chat changes (switch, open or close), with the newly active id. */
    public interface ActiveSessionListener {
        void activeSessionChanged(ChatSessionId id);
    }

    /**
     * Notified whenever the SET of open chats changes (open or close), with the current open ids in
     * order. The host persists this immediately so a crash never resurrects a closed chat.
     */
    public interface TabSetListener {
        void tabSetChanged(List<ChatSessionId> openIds);
    }

    private static final int SIDEBAR_CLOSE_DELAY_MS = 300;
    private static final int HOVER_MARGIN_PX = 12;

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardLayout);
    private final ChatSessionFactory factory;
    private final ChatHistoryStore historyStore; // may be null (tests) → only open sessions are listed
    private final Map<ChatSessionId, ChatSessionComponent> sessionsById =
            new LinkedHashMap<ChatSessionId, ChatSessionComponent>();
    private ChatSessionId activeId;
    private ActiveSessionListener activeSessionListener;
    private TabSetListener tabSetListener;
    private ChatSessionId lastNotifiedSessionId;

    private final ChatSidebarPanel sidebar;
    private final JButton burger;
    /** Slot for the ACTIVE agent's TRAILING toolbar contributions, far right (may stay empty). */
    private final JPanel agentToolbarSlot = new JPanel(new BorderLayout());
    /** Slot for the ACTIVE agent's Chats-footer control (e.g. the session language pill). */
    private final JPanel agentFooterToolbarSlot = new JPanel(new BorderLayout());
    /**
     * The CENTERED top-bar slot (e.g. the research web-search tag): GridBagLayout centers its one
     * child; the unfolding ribbon pushes it right and squeezes it when the width runs out.
     */
    private final JPanel agentCenterSlot = new JPanel(new java.awt.GridBagLayout());
    /** The drawer's chat search bar — filters the chat list live by title/project. */
    private final com.aresstack.comiccontrols.control.ComicSearchBar chatFilter =
            new com.aresstack.comiccontrols.control.ComicSearchBar(
                    "Chats durchsuchen…", "Filtert die Chatliste nach Titel und Projekt");
    private final com.aresstack.askai.java8.ui.sidebar.SidebarTabRibbon ribbon =
            new com.aresstack.askai.java8.ui.sidebar.SidebarTabRibbon();
    private JPanel chatListPanel;
    private java.util.function.Supplier<List<ChatSidebarTab>> sidebarTabsSource;
    private final javax.swing.Timer sidebarCloseTimer;
    private AWTEventListener sidebarMouseWatcher;
    private boolean menuLocked; // clicking the burger latches the menu until the next click
    private com.aresstack.askai.java8.state.ApplicationStateService applicationState;
    private static final String STATE_BURGER_PINNED = "chat.burgerPinned";
    private static final String STATE_SIDEBAR_WIDTH = "chat.sidebar.width";
    private static final int SIDEBAR_MIN_WIDTH = 280;
    private static final int SIDEBAR_MAX_WIDTH = 700;
    private static final int SIDEBAR_DEFAULT_WIDTH = 360;
    private static final int SIDEBAR_WIDTH_SAVE_DELAY_MS = 300;

    /** Sidebar and cards share this pane; the divider only exists while the drawer is open. */
    private final ComicSplitPane sidebarSplit;
    /** Debounces the per-drag divider events into ONE state write after the drag settles. */
    private final javax.swing.Timer sidebarWidthSaveTimer;
    private int pendingSidebarWidth = -1;

    public ChatWorkspacePanel(ChatSessionFactory factory) {
        this(factory, null, null);
    }

    public ChatWorkspacePanel(ChatSessionFactory factory, List<ChatSessionId> restoreIds) {
        this(factory, restoreIds, null);
    }

    /**
     * @param restoreIds   session ids of persisted chats to reopen on startup (most recent first);
     *                     when null/empty, a single fresh chat is opened
     * @param historyStore the saved-chats store backing the sidebar list (null in tests)
     */
    public ChatWorkspacePanel(ChatSessionFactory factory, List<ChatSessionId> restoreIds,
                              ChatHistoryStore historyStore) {
        super(new BorderLayout());
        if (factory == null) {
            throw new IllegalArgumentException("factory must not be null");
        }
        this.factory = factory;
        this.historyStore = historyStore;

        this.burger = ChatComposerPanel.createSidebarToggleButton();
        this.sidebar = new ChatSidebarPanel("Chats", buildChatsSidebarTab());
        this.sidebarSplit = new ComicSplitPane(sidebar, cards, SIDEBAR_MIN_WIDTH, SIDEBAR_MAX_WIDTH);
        sidebarSplit.setPreferredLeftWidth(SIDEBAR_DEFAULT_WIDTH);
        sidebarSplit.collapseLeft(); // the drawer starts closed, exactly like before the split pane
        this.sidebarWidthSaveTimer = new javax.swing.Timer(SIDEBAR_WIDTH_SAVE_DELAY_MS,
                event -> saveSidebarWidthNow());
        sidebarWidthSaveTimer.setRepeats(false);
        sidebarSplit.setLeftWidthListener(width -> {
            pendingSidebarWidth = width;
            sidebarWidthSaveTimer.restart();
        });
        this.sidebarCloseTimer = new javax.swing.Timer(SIDEBAR_CLOSE_DELAY_MS,
                event -> onPointerLeftSidebarArea());
        sidebarCloseTimer.setRepeats(false);
        buildTopLevelLayout();

        if (restoreIds != null && !restoreIds.isEmpty()) {
            // Reopen persisted chats in oldest-first order (the list is newest-first).
            for (int i = restoreIds.size() - 1; i >= 0; i--) {
                openExistingChat(restoreIds.get(i));
            }
        } else {
            openNewChat(); // never start empty
        }
    }

    // ------------------------------------------------------------------ public session API

    /** Open a persisted chat with a known id (selects it when already open); restores its transcript. */
    public ChatSessionComponent openExistingChat(ChatSessionId id) {
        ChatSessionComponent existing = sessionsById.get(id);
        if (existing != null) {
            selectSession(id);
            return existing;
        }
        return addChat(id, factory.create(id));
    }

    /** Create a brand-new chat and bring it to the foreground. */
    public ChatSessionComponent openNewChat() {
        ChatSessionId id = ChatSessionId.create();
        return addChat(id, factory.create(id));
    }

    /**
     * THE one way a chat enters the workspace. Restored at startup or opened at runtime, the steps must be
     * identical — the two used to be separate copies of the same four lines, which is exactly the kind of
     * duplication where the two paths silently drift apart.
     */
    private ChatSessionComponent addChat(ChatSessionId id, ChatSessionComponent session) {
        sessionsById.put(id, session);
        cards.add(session.getComponent(), id.toString());
        selectSession(id);
        fireTabSetChanged();
        return session;
    }

    /** Bring the chat with this id to the foreground (no-op for unknown ids). */
    public void selectSession(ChatSessionId id) {
        ChatSessionComponent session = sessionsById.get(id);
        if (session == null) {
            return;
        }
        cardLayout.show(cards, id.toString());
        activeId = id;
        fireActiveSessionChanged();
        if (sidebar.isVisible()) {
            refreshChatList(); // update the "current" marker
        }
    }

    /** Close the chat with this id: abort its work, remove its card, and never leave the workspace empty. */
    public void closeSession(ChatSessionId id) {
        ChatSessionComponent session = sessionsById.remove(id);
        if (session == null) {
            return;
        }
        try {
            session.disposeSession();
        } finally {
            cards.remove(session.getComponent());
        }
        if (sessionsById.isEmpty()) {
            openNewChat(); // fires the active-session + tab-set change for the fresh replacement itself
        } else {
            if (id.equals(activeId)) {
                // Fall back to the most recently opened remaining chat.
                ChatSessionId last = null;
                for (ChatSessionId open : sessionsById.keySet()) {
                    last = open;
                }
                selectSession(last);
            }
            fireTabSetChanged(); // persist "this chat is closed" immediately
            if (sidebar.isVisible()) {
                refreshChatList();
            }
        }
    }

    /**
     * Register the active-session listener; fires immediately with the current selection so the host can
     * restore that chat's mode/agent synchronously. Switching, opening or closing chats re-fires it.
     */
    public void setActiveSessionListener(ActiveSessionListener listener) {
        this.activeSessionListener = listener;
        fireActiveSessionChanged();
    }

    /**
     * Register the open-set listener; fires immediately with the currently open ids so the host can
     * persist the restored set. Every later open/close re-fires it so "closed stays closed".
     */
    public void setTabSetListener(TabSetListener listener) {
        this.tabSetListener = listener;
        fireTabSetChanged();
    }

    /** @return the currently selected chat session, or null if none. */
    public ChatSessionComponent activeSession() {
        return activeId == null ? null : sessionsById.get(activeId);
    }

    /** @return a snapshot of all open sessions (e.g. for a global catalog refresh or shutdown). */
    public List<ChatSessionComponent> sessions() {
        return new ArrayList<ChatSessionComponent>(sessionsById.values());
    }

    /** Plugin seam: contributions appear as additional sidebar tabs whenever the drawer opens. */
    public void setSidebarTabContributions(
            java.util.function.Supplier<List<ChatSidebarTab>> source) {
        this.sidebarTabsSource = source;
    }

    /**
     * Bind the application state so the burger's PINNED (latched) drawer survives a restart. Restores the
     * pinned drawer immediately when the last session left it pinned. Safe to call once after construction.
     */
    public void setApplicationState(com.aresstack.askai.java8.state.ApplicationStateService applicationState) {
        this.applicationState = applicationState;
        if (applicationState != null) {
            // Restore the user's drawer width BEFORE a pinned drawer reopens below.
            int width = parseWidth(applicationState.get(STATE_SIDEBAR_WIDTH, null));
            if (width > 0) {
                sidebarSplit.setPreferredLeftWidth(width);
            }
        }
        if (applicationState != null && applicationState.getBoolean(STATE_BURGER_PINNED, false)) {
            menuLocked = true;
            ChatComposerPanel.setToolbarButtonLatched(burger, true);
            openMenuAndSidebar();
            refreshRibbonTabs();
        }
    }

    /** Persist the current pinned/latched state of the burger drawer (no-op before the state is bound). */
    private void persistBurgerPinned() {
        if (applicationState != null) {
            applicationState.putAndSave(STATE_BURGER_PINNED, Boolean.toString(menuLocked));
        }
    }

    /** Write the debounced drawer width to the application state (no-op before the state is bound). */
    private void saveSidebarWidthNow() {
        sidebarWidthSaveTimer.stop();
        if (applicationState != null && pendingSidebarWidth > 0) {
            applicationState.putAndSave(STATE_SIDEBAR_WIDTH, Integer.toString(pendingSidebarWidth));
        }
    }

    private static int parseWidth(String value) {
        if (value == null) {
            return -1;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException invalid) {
            return -1; // a corrupt persisted width falls back to the default
        }
    }

    /**
     * Re-read the chat list in place — used when a chat's PERSISTED state changed (a title appeared, a
     * message was written). Without this the open sidebar keeps showing "(new chat)" for a chat that has
     * long since been titled, e.g. one an agent created with an explicit title.
     */
    public void refreshChatTitles() {
        if (sidebar.isVisible()) {
            refreshChatList();
        }
    }

    /** Refresh the drawer's panes/ribbon in place (e.g. the active agent's tab set changed). */
    public void refreshSidebarTabs() {
        if (sidebar.isVisible()) {
            sidebar.rebuildTabs();
            refreshRibbonTabs();
        }
    }

    /**
     * Open the drawer LATCHED on the pane with this title (e.g. an /open artifact reveal): an
     * explicitly requested pane must not fold away on the next mouse move.
     */
    public void openSidebarTab(String title) {
        menuLocked = true;
        ChatComposerPanel.setToolbarButtonLatched(burger, true);
        openMenuAndSidebar();
        sidebar.showTab(title);
        refreshRibbonTabs();
        persistBurgerPinned();
    }

    /** Show the active agent's control in the Chats-pane footer (replaces any previous one). */
    public void setAgentFooterToolbar(javax.swing.JComponent component) {
        agentFooterToolbarSlot.removeAll();
        if (component != null) {
            agentFooterToolbarSlot.add(component, BorderLayout.CENTER);
        }
        agentFooterToolbarSlot.revalidate();
        agentFooterToolbarSlot.repaint();
    }

    public void clearAgentFooterToolbar() {
        setAgentFooterToolbar(null);
    }

    /** Show the active agent's TRAILING toolbar controls far right (replaces any previous ones). */
    public void setAgentToolbar(javax.swing.JComponent component) {
        agentToolbarSlot.removeAll();
        if (component != null) {
            agentToolbarSlot.add(component, BorderLayout.CENTER);
        }
        agentToolbarSlot.revalidate();
        agentToolbarSlot.repaint();
    }

    public void clearAgentToolbar() {
        setAgentToolbar(null);
    }

    /** Show the active agent's CENTERED top-bar control (e.g. the research web-search tag). */
    public void setAgentCenterToolbar(javax.swing.JComponent component) {
        agentCenterSlot.removeAll();
        if (component != null) {
            agentCenterSlot.add(component); // GridBag default constraints → centered
        }
        agentCenterSlot.revalidate();
        agentCenterSlot.repaint();
    }

    public void clearAgentCenterToolbar() {
        setAgentCenterToolbar(null);
    }

    // ------------------------------------------------------------------ layout + sidebar behavior

    private void buildTopLevelLayout() {
        // Opening/closing is HOVER-only; a click LATCHES the ribbon instead: the burger stays
        // pressed-in until the next click, which releases and closes the menu.
        burger.addActionListener(event -> {
            if (menuLocked) {
                collapseMenuAndSidebar(); // also resets menuLocked and the pressed-in look
            } else {
                menuLocked = true;
                ChatComposerPanel.setToolbarButtonLatched(burger, true);
                openMenuAndSidebar();
            }
            persistBurgerPinned(); // remember the pin across restarts
        });
        burger.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                if (!sidebar.isVisible() && !ribbon.isOpen()) {
                    openMenuAndSidebar(); // hovering the burger unfolds the menu + drawer
                }
            }
        });

        ribbon.setListener(title -> {
            if (!sidebar.isVisible()) {
                showSidebar();
            }
            sidebar.showTab(title);
            refreshRibbonTabs(); // re-emphasize the newly active entry
        });

        JPanel topBar = new JPanel(new BorderLayout(4, 0));
        topBar.setOpaque(false);
        topBar.setBorder(BorderFactory.createEmptyBorder(2, 4, 0, 4));
        // LEFT: burger + the ribbon, which now asks only for its unfolded share — so the centered
        // slot owns the middle and gets pushed right/squeezed while the ribbon unfolds.
        JPanel topLeft = new JPanel();
        topLeft.setLayout(new javax.swing.BoxLayout(topLeft, javax.swing.BoxLayout.X_AXIS));
        topLeft.setOpaque(false);
        topLeft.add(burger);
        topLeft.add(ribbon);
        topBar.add(topLeft, BorderLayout.WEST);
        agentCenterSlot.setOpaque(false);
        topBar.add(agentCenterSlot, BorderLayout.CENTER);
        // The agent's TRAILING contributions (e.g. Websuche + Mindmap) sit at the FAR right — the
        // old "+"/gear pair moved into the drawer's Chats pane. The workspace only hosts the slot;
        // components come from the plugin.
        agentToolbarSlot.setOpaque(false);
        topBar.add(agentToolbarSlot, BorderLayout.EAST);

        sidebar.setVisible(false);
        sidebar.setExtraTabsSupplier(() -> sidebarTabsSource == null
                ? java.util.Collections.<ChatSidebarTab>emptyList() : sidebarTabsSource.get());
        // No pane title in the header — the ribbon's colored entry already names the active pane.
        // The chat SEARCH BAR takes that spot; it filters live on every keystroke, no Enter needed.
        chatFilter.getTextField().getDocument().addDocumentListener(
                new javax.swing.event.DocumentListener() {
                    public void insertUpdate(javax.swing.event.DocumentEvent e) {
                        refreshChatList();
                    }

                    public void removeUpdate(javax.swing.event.DocumentEvent e) {
                        refreshChatList();
                    }

                    public void changedUpdate(javax.swing.event.DocumentEvent e) {
                        refreshChatList();
                    }
                });
        sidebar.setHeaderComponent(chatFilter);

        add(topBar, BorderLayout.NORTH);
        // Drawer and cards live in the comic split pane: while the drawer is collapsed there is no
        // divider at all, so this looks exactly like the old WEST/CENTER layout — but an open
        // drawer can be resized by mouse (issue #36).
        add(sidebarSplit, BorderLayout.CENTER);
    }

    private void refreshRibbonTabs() {
        ribbon.setTabs(sidebar.tabTitles(), sidebar.activeTab());
    }

    private void openMenuAndSidebar() {
        showSidebar();
        ribbon.open();
    }

    private void collapseMenuAndSidebar() {
        sidebarCloseTimer.stop();
        menuLocked = false;
        ChatComposerPanel.setToolbarButtonLatched(burger, false);
        ribbon.close();
        hideSidebar();
        persistBurgerPinned();
    }

    private void showSidebar() {
        refreshChatList();
        sidebar.rebuildTabs(); // picks up freshly contributed panes
        refreshRibbonTabs();
        sidebar.setVisible(true);
        sidebarSplit.openLeft(); // brings back the divider at the remembered width
        updateMouseWatcher();
        lastBusySignature = busySignature();
        activityRefreshTimer.start();
        revalidate();
        repaint();
    }

    private void hideSidebar() {
        activityRefreshTimer.stop();
        sidebar.setVisible(false);
        sidebarSplit.collapseLeft(); // width 0, divider gone
        updateMouseWatcher();
        revalidate();
        repaint();
    }

    /**
     * While the drawer is open, the activity dots must not go stale: a quiet poll compares the
     * busy SET (from the sessions' authoritative runtime state) and rebuilds the list only when it
     * actually changed — so hover states never flicker on idle ticks.
     */
    private java.util.Set<ChatSessionId> lastBusySignature = java.util.Collections.emptySet();
    private final javax.swing.Timer activityRefreshTimer =
            new javax.swing.Timer(2500, event -> onActivityPollTick());

    private void onActivityPollTick() {
        if (!sidebar.isVisible()) {
            return;
        }
        java.util.Set<ChatSessionId> signature = busySignature();
        if (!signature.equals(lastBusySignature)) {
            lastBusySignature = signature;
            refreshChatList();
        }
    }

    private java.util.Set<ChatSessionId> busySignature() {
        java.util.Set<ChatSessionId> busy = new java.util.HashSet<ChatSessionId>();
        for (ChatSessionId id : sessionsById.keySet()) {
            if (isSessionBusy(id)) {
                busy.add(id);
            }
        }
        return busy;
    }

    /** Ribbon and drawer fold away TOGETHER after hover-out — unless latched by the burger click. */
    private void onPointerLeftSidebarArea() {
        if (!menuLocked) {
            ribbon.close();
            hideSidebar();
        }
        updateMouseWatcher();
    }

    /**
     * While the drawer or the unfolded menu is open, a global mouse watcher tracks the pointer: it
     * stays inside the burger ∪ ribbon ∪ drawer area → nothing happens; it leaves → a short delay
     * (bridging the burger→drawer transit) folds away whatever is neither pinned nor latched.
     */
    private void updateMouseWatcher() {
        boolean needed = sidebar.isVisible() || ribbon.isOpen();
        if (needed) {
            installSidebarMouseWatcher();
        } else {
            removeSidebarMouseWatcher();
        }
    }

    private void installSidebarMouseWatcher() {
        if (sidebarMouseWatcher != null) {
            return;
        }
        sidebarMouseWatcher = new AWTEventListener() {
            public void eventDispatched(AWTEvent event) {
                if (!(event instanceof MouseEvent) || !isShowing()) {
                    return;
                }
                if (menuLocked) {
                    sidebarCloseTimer.stop(); // latched: nothing auto-closes until the next click
                    return;
                }
                MouseEvent mouse = (MouseEvent) event;
                int id = mouse.getID();
                if (id != MouseEvent.MOUSE_MOVED && id != MouseEvent.MOUSE_ENTERED
                        && id != MouseEvent.MOUSE_DRAGGED) {
                    return;
                }
                Point onScreen = new Point(mouse.getXOnScreen(), mouse.getYOnScreen());
                boolean inside = screenBounds(burger, HOVER_MARGIN_PX).contains(onScreen)
                        || (ribbon.isOpen() && screenBounds(ribbon, HOVER_MARGIN_PX).contains(onScreen))
                        || (sidebar.isVisible() && screenBounds(sidebar, HOVER_MARGIN_PX).contains(onScreen));
                if (inside) {
                    sidebarCloseTimer.stop();
                } else if (!sidebarCloseTimer.isRunning()) {
                    sidebarCloseTimer.restart();
                }
            }
        };
        try {
            Toolkit.getDefaultToolkit().addAWTEventListener(sidebarMouseWatcher,
                    AWTEvent.MOUSE_MOTION_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK);
        } catch (SecurityException restricted) {
            sidebarMouseWatcher = null; // hover-close degrades gracefully; pin/close still work
        }
    }

    private void removeSidebarMouseWatcher() {
        if (sidebarMouseWatcher != null) {
            try {
                Toolkit.getDefaultToolkit().removeAWTEventListener(sidebarMouseWatcher);
            } catch (SecurityException ignore) {
            }
            sidebarMouseWatcher = null;
        }
    }

    private static Rectangle screenBounds(Component component, int margin) {
        if (!component.isShowing()) {
            return new Rectangle(0, 0, 0, 0);
        }
        Point location = component.getLocationOnScreen();
        return new Rectangle(location.x - margin, location.y - margin,
                component.getWidth() + 2 * margin, component.getHeight() + 2 * margin);
    }

    // ------------------------------------------------------------------ the "Chats" sidebar tab

    /**
     * The default sidebar tab REPLACES the old bottom tab strip: one list that merges the open
     * sessions (in workspace order) with the remaining saved history, plus New-chat on top and the
     * confirmed delete-all at the bottom.
     */
    private JComponent buildChatsSidebarTab() {
        chatListPanel = new JPanel();
        chatListPanel.setLayout(new javax.swing.BoxLayout(chatListPanel, javax.swing.BoxLayout.Y_AXIS));
        chatListPanel.setOpaque(false);
        JScrollPane scroll = new ComicScrollPane(chatListPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        // "+ Neuer Chat" above the list — the design study's dark pill, only as wide as its text.
        ResearchPillButton newChat = new ResearchPillButton("+ Neuer Chat",
                ResearchUiMetrics.NEW_CHAT_HEIGHT, ResearchUiMetrics.RADIUS_CONTROL,
                ResearchUiMetrics.NEW_CHAT_PADDING_H);
        newChat.setFont(ResearchUiTypography.semiBold(13f));
        newChat.setToolTipText("Open a new chat");
        newChat.addActionListener(event -> {
            openNewChat();
            if (menuLocked) {
                refreshChatList();
            } else {
                collapseMenuAndSidebar();
            }
        });
        JPanel north = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        north.setOpaque(false);
        north.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        north.add(newChat);

        JPanel tab = new JPanel(new BorderLayout());
        tab.setOpaque(false);
        tab.add(north, BorderLayout.NORTH);
        tab.add(scroll, BorderLayout.CENTER);
        tab.add(buildChatsFooter(), BorderLayout.SOUTH);
        return tab;
    }

    /**
     * The Chats-pane FOOTER: [language slot] [Delete all chats…] [gear] in ONE row, pinned to the
     * bottom of the pane (it never scrolls with the list). The language control is the active
     * agent's SIDEBAR_FOOTER toolbar contribution; delete-all lost its own full-width row — it is a
     * small quiet control now that only turns red on hover (the confirmation logic is unchanged);
     * the gear opens the active chat's settings dialog.
     */
    private JComponent buildChatsFooter() {
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(
                ResearchUiMetrics.FOOTER_PADDING_V, ResearchUiMetrics.FOOTER_PADDING_H,
                ResearchUiMetrics.FOOTER_PADDING_V, ResearchUiMetrics.FOOTER_PADDING_H));
        footer.setPreferredSize(new Dimension(10, ResearchUiMetrics.FOOTER_HEIGHT));

        agentFooterToolbarSlot.setOpaque(false);
        footer.add(agentFooterToolbarSlot, BorderLayout.WEST);

        // Delete-all: same height/radius family as the language pill; white and quiet at rest,
        // red only on hover/pressed — a rarely used destructive action must not draw attention.
        ResearchPillButton deleteAll = new ResearchPillButton("Delete all chats…",
                ResearchUiMetrics.FOOTER_CONTROL_HEIGHT, ResearchUiMetrics.RADIUS_CONTROL, 13);
        deleteAll.setFont(ResearchUiTypography.regular(12.5f));
        deleteAll.setToolTipText("Delete every saved chat (asks for confirmation)");
        deleteAll.setFills(ResearchUiPalette.LIGHT_CONTROL_BG, ResearchUiPalette.DANGER_RED,
                ResearchUiPainter.mix(ResearchUiPalette.DANGER_RED, java.awt.Color.BLACK, 0.18f));
        deleteAll.setBorders(ResearchUiPalette.LIGHT_CONTROL_BORDER, ResearchUiPalette.DANGER_RED,
                ResearchUiPainter.mix(ResearchUiPalette.DANGER_RED, java.awt.Color.BLACK, 0.18f));
        deleteAll.setForegrounds(ResearchUiPalette.LIGHT_CONTROL_TEXT, java.awt.Color.WHITE,
                java.awt.Color.WHITE);
        deleteAll.addActionListener(event -> deleteAllChats());
        JPanel deleteWrap = new JPanel(new java.awt.GridBagLayout());
        deleteWrap.setOpaque(false);
        deleteWrap.add(deleteAll);
        footer.add(deleteWrap, BorderLayout.CENTER);

        ResearchIconButton gear = new ResearchIconButton(
                ChatComposerPanel.createGearGlyphIcon(), "Chat settings");
        gear.addActionListener(event -> {
            ChatSessionComponent active = activeSession();
            if (active instanceof OllamaChatPanel) {
                ((OllamaChatPanel) active).openSettingsDialog();
            }
        });
        JPanel gearWrap = new JPanel(new java.awt.GridBagLayout());
        gearWrap.setOpaque(false);
        gearWrap.add(gear);
        footer.add(gearWrap, BorderLayout.EAST);
        return footer;
    }

    /** One chat-list entry: an open session ({@code openId != null}) and/or its persisted record. */
    private static final class ChatListEntry {
        final ChatSessionId openId;
        final ChatRecord record;

        ChatListEntry(ChatSessionId openId, ChatRecord record) {
            this.openId = openId;
            this.record = record;
        }
    }

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    /**
     * Rebuild the list, grouped for the drawer's two-line history rows: AKTIV holds ONLY chats in
     * which processing is actually running right now (authoritative runtime state, see {@link
     * OllamaChatPanel#isProcessingBusy()}) — an idle open session is ordinary history. Then the
     * PROJECT groups (recency order of first appearance), then everything else by age — HEUTE,
     * GESTERN, LETZTE 7 TAGE, ÄLTER. Empty groups are simply absent (including AKTIV). The search
     * bar filters every section live by title and project name.
     */
    private void refreshChatList() {
        chatListPanel.removeAll();
        List<ChatRecord> saved = historyStore != null ? historyStore.list()
                : java.util.Collections.<ChatRecord>emptyList();
        Map<String, ChatRecord> savedById = new LinkedHashMap<String, ChatRecord>();
        for (ChatRecord record : saved) {
            savedById.put(record.getId(), record);
        }
        String filter = chatFilter.getText().trim().toLowerCase(java.util.Locale.ROOT);
        long startOfToday = startOfToday();

        List<ChatListEntry> entries = new ArrayList<ChatListEntry>();
        for (ChatSessionId id : sessionsById.keySet()) {
            entries.add(new ChatListEntry(id, savedById.remove(id.toString())));
        }
        for (ChatRecord record : savedById.values()) {
            entries.add(new ChatListEntry(null, record));
        }

        Map<String, List<ChatListEntry>> byProject = new LinkedHashMap<String, List<ChatListEntry>>();
        List<ChatListEntry> active = new ArrayList<ChatListEntry>();
        List<ChatListEntry> today = new ArrayList<ChatListEntry>();
        List<ChatListEntry> yesterday = new ArrayList<ChatListEntry>();
        List<ChatListEntry> lastWeek = new ArrayList<ChatListEntry>();
        List<ChatListEntry> older = new ArrayList<ChatListEntry>();
        for (ChatListEntry entry : entries) {
            String project = entry.record == null ? null : entry.record.getProject();
            if (!matchesFilter(entry, project, filter)) {
                continue;
            }
            if (isSessionBusy(entry.openId)) {
                active.add(entry); // ACTUALLY working right now — busy wins over project/time
            } else if (project != null) {
                List<ChatListEntry> group = byProject.get(project);
                if (group == null) {
                    group = new ArrayList<ChatListEntry>();
                    byProject.put(project, group);
                }
                group.add(entry);
            } else {
                // Idle chats (open or not) are ordinary history, bucketed by their last change;
                // a freshly opened chat without a persisted record belongs to today.
                long at = entry.record == null ? startOfToday : entry.record.getModifiedAt();
                if (at >= startOfToday) {
                    today.add(entry);
                } else if (at >= startOfToday - DAY_MS) {
                    yesterday.add(entry);
                } else if (at >= startOfToday - 6 * DAY_MS) {
                    lastWeek.add(entry);
                } else {
                    older.add(entry);
                }
            }
        }

        addChatGroup("AKTIV", active, startOfToday);
        for (Map.Entry<String, List<ChatListEntry>> group : byProject.entrySet()) {
            chatListPanel.add(projectHeader(group.getKey()));
            for (ChatListEntry entry : group.getValue()) {
                chatListPanel.add(buildRow(entry.openId, entry.record, startOfToday));
            }
        }
        addChatGroup("HEUTE", today, startOfToday);
        addChatGroup("GESTERN", yesterday, startOfToday);
        addChatGroup("LETZTE 7 TAGE", lastWeek, startOfToday);
        addChatGroup("ÄLTER", older, startOfToday);
        if (chatListPanel.getComponentCount() == 0) {
            JLabel none = new JLabel(filter.isEmpty() ? "No chats" : "No matching chats");
            none.setEnabled(false);
            none.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            chatListPanel.add(none);
        }
        chatListPanel.revalidate();
        chatListPanel.repaint();
    }

    /** One time/state group: quiet header + its rows; an empty group renders nothing at all. */
    private void addChatGroup(String title, List<ChatListEntry> group, long startOfToday) {
        if (group.isEmpty()) {
            return;
        }
        chatListPanel.add(groupHeader(title));
        for (ChatListEntry entry : group) {
            chatListPanel.add(buildRow(entry.openId, entry.record, startOfToday));
        }
    }

    /** Midnight of the local calendar day — the boundary for HEUTE/GESTERN/… bucketing. */
    private static long startOfToday() {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0);
        calendar.set(java.util.Calendar.MINUTE, 0);
        calendar.set(java.util.Calendar.SECOND, 0);
        calendar.set(java.util.Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static boolean matchesFilter(ChatListEntry entry, String project, String filter) {
        if (filter.isEmpty()) {
            return true;
        }
        String title = rowTitle(entry.openId, entry.record).toLowerCase(java.util.Locale.ROOT);
        return title.contains(filter)
                || (project != null && project.toLowerCase(java.util.Locale.ROOT).contains(filter));
    }

    /** A project group's heading — the quiet group typography, petrol so projects stay distinct. */
    private JComponent projectHeader(String project) {
        JLabel header = new JLabel("▪ " + project);
        header.setFont(ResearchUiTypography.semiBold(11f));
        header.setForeground(new java.awt.Color(0x15827A)); // the design language's petrol role
        header.setBorder(BorderFactory.createEmptyBorder(14, 10, 4, 8));
        return header;
    }

    /** A time/state group heading (AKTIV, HEUTE, …): 11px Semi Bold, muted, extra top spacing. */
    private JComponent groupHeader(String title) {
        JLabel header = new JLabel(title);
        header.setFont(ResearchUiTypography.semiBold(11f));
        header.setForeground(com.aresstack.comiccontrols.theme.ResearchUiPalette.LIGHT_TEXT_MUTED);
        header.setBorder(BorderFactory.createEmptyBorder(14, 10, 4, 8));
        return header;
    }

    private static String rowTitle(ChatSessionId openId, ChatRecord record) {
        return record != null && record.getTitle() != null && !record.getTitle().trim().isEmpty()
                ? record.getTitle().trim()
                : (openId != null ? "(new chat)" : "(untitled)");
    }

    /**
     * One two-line history row ({@link ChatHistoryRow}): status dot, title, time, quiet metadata.
     * Clicking the row brings the chat to the foreground (opening it first when needed); ALL other
     * actions (close, delete, project) live behind the hover-only {@code …} / right-click menu —
     * the same existing functions the old inline ✕/🗑 buttons and the right-click menu called.
     */
    private JComponent buildRow(final ChatSessionId openId, final ChatRecord record,
                                long startOfToday) {
        final String chatId = openId != null ? openId.toString() : record.getId();
        final String title = rowTitle(openId, record);
        if (chatId.equals(renamingChatId)) {
            return buildRenameRow(openId, record, title);
        }
        Runnable open = () -> {
            try {
                ChatSessionId target = openId != null ? openId
                        : new ChatSessionId(java.util.UUID.fromString(chatId));
                openExistingChat(target); // selects when already open — the tab-switch replacement
            } catch (IllegalArgumentException ignored) {
                return;
            }
            if (!menuLocked) {
                collapseMenuAndSidebar();
            }
        };
        boolean busy = isSessionBusy(openId);
        return new ChatHistoryRow(title,
                rowMeta(openId, record, busy, startOfToday),
                rowTime(record, startOfToday),
                busy, openId != null && openId.equals(activeId),
                open, () -> buildRowMenu(openId, record, title));
    }

    /** True when this chat's session ACTUALLY processes something right now (never "it exists"). */
    private boolean isSessionBusy(ChatSessionId openId) {
        ChatSessionComponent session = openId == null ? null : sessionsById.get(openId);
        return session instanceof OllamaChatPanel
                && ((OllamaChatPanel) session).isProcessingBusy();
    }

    /** The hover/right-click menu of one row — existing functions only, nothing new. */
    private javax.swing.JPopupMenu buildRowMenu(final ChatSessionId openId, final ChatRecord record,
                                                final String title) {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        // Rename morphs the ROW into an inline field (like the sky's + Add), no dialog.
        javax.swing.JMenuItem rename = new javax.swing.JMenuItem("Rename…");
        rename.addActionListener(event -> {
            renamingChatId = openId != null ? openId.toString() : record.getId();
            refreshChatList();
        });
        menu.add(rename);
        if (openId != null) {
            javax.swing.JMenuItem close = new javax.swing.JMenuItem("Close this chat");
            close.addActionListener(event -> closeSession(openId));
            menu.add(close);
        }
        if (record != null) {
            javax.swing.JMenuItem assign = new javax.swing.JMenuItem(
                    record.getProject() == null ? "Assign to project…" : "Move to project…");
            assign.addActionListener(event -> openAssignProjectDialog(openId, record));
            menu.add(assign);
            if (record.getProject() != null) {
                javax.swing.JMenuItem remove = new javax.swing.JMenuItem(
                        "Remove from \"" + record.getProject() + "\"");
                remove.addActionListener(event -> applyProject(openId, record, null));
                menu.add(remove);
            }
            javax.swing.JMenuItem delete = new javax.swing.JMenuItem("Delete this saved chat…");
            delete.addActionListener(event -> deletePersistedChat(record, title));
            menu.add(delete);
        }
        return menu;
    }

    /** The chat currently morphing into an inline rename field (its id), or {@code null}. */
    private String renamingChatId;

    /**
     * The rename "Verwandlung": the row becomes [field | comic ✕] — Enter applies, Escape or the
     * shared close chip (the same one the sky's add field uses) cancels. No dialog.
     */
    private JComponent buildRenameRow(final ChatSessionId openId, final ChatRecord record,
                                      String title) {
        final javax.swing.JTextField field = new javax.swing.JTextField(title);
        field.setFont(ResearchUiTypography.regular(13f));
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ResearchUiPainter.mix(
                        ResearchUiPalette.ACCENT_BLUE, java.awt.Color.WHITE, 0.5f)),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        final Runnable cancel = () -> {
            renamingChatId = null;
            refreshChatList();
        };
        field.addActionListener(event -> {
            String value = field.getText();
            renamingChatId = null;
            applyRename(openId, record, value);
        });
        field.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent event) {
                if (event.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE) {
                    cancel.run();
                }
            }
        });
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        row.add(field, BorderLayout.CENTER);
        com.aresstack.comiccontrols.control.ComicOverlayPanel.CloseButton close =
                new com.aresstack.comiccontrols.control.ComicOverlayPanel.CloseButton(
                        com.aresstack.comiccontrols.theme.ComicPalette.defaultPalette(),
                        cancel);
        close.setToolTipText("Cancel");
        JPanel closeWrap = new JPanel(new java.awt.GridBagLayout());
        closeWrap.setOpaque(false);
        closeWrap.add(close);
        row.add(closeWrap, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        javax.swing.SwingUtilities.invokeLater(() -> {
            field.requestFocusInWindow();
            field.selectAll();
        });
        return row;
    }

    /**
     * Persist the new title. An OPEN chat owns its live record (the panel autosaves it), so the
     * change must go through the panel — exactly like {@link #applyProject}. Blank keeps the old
     * title (a rename to nothing is a cancel, not a deletion).
     */
    void applyRename(ChatSessionId openId, ChatRecord record, String newTitle) {
        String trimmed = newTitle == null ? "" : newTitle.trim();
        if (!trimmed.isEmpty()) {
            ChatSessionComponent session = openId == null ? null : sessionsById.get(openId);
            if (session instanceof OllamaChatPanel) {
                ((OllamaChatPanel) session).setChatTitle(trimmed);
            } else if (record != null && historyStore != null) {
                record.setTitle(trimmed);
                historyStore.save(record);
            }
        }
        refreshChatList();
    }

    /** Delete ONE saved chat after the existing confirmation — unchanged safety logic. */
    private void deletePersistedChat(ChatRecord record, String title) {
        int choice = JOptionPane.showConfirmDialog(this,
                "Delete the saved chat \"" + title + "\"? This cannot be undone.",
                "Delete chat", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.OK_OPTION && historyStore != null) {
            historyStore.delete(record.getId());
            detachOpenPanelFromDeletedChat(record.getId());
            refreshChatList();
        }
    }

    /**
     * Line 2: what is actually known and useful — never a repetitive "Aktive Sitzung". Busy chats
     * say so ("Research Agent · arbeitet"); idle open chats show agent · phase when available;
     * everything else falls back to the quiet time metadata.
     */
    private String rowMeta(ChatSessionId openId, ChatRecord record, boolean busy,
                           long startOfToday) {
        ChatSessionComponent session = openId == null ? null : sessionsById.get(openId);
        if (session instanceof OllamaChatPanel) {
            OllamaChatPanel panel = (OllamaChatPanel) session;
            String agent = panel.describeActiveAgentForList();
            if (busy) {
                return agent == null || agent.trim().isEmpty() ? "arbeitet"
                        : agent + " · arbeitet";
            }
            String phase = titleCase(panel.describeAgentPhaseForList());
            if (agent != null && !agent.trim().isEmpty()) {
                return phase.isEmpty() ? agent : agent + " · " + phase;
            }
        }
        return savedMeta(record, startOfToday);
    }

    /** "SCOPING" → "Scoping" (the list speaks quietly, not in enum shouting). */
    private static String titleCase(String label) {
        if (label == null || label.trim().isEmpty()) {
            return "";
        }
        String trimmed = label.trim();
        return Character.toUpperCase(trimmed.charAt(0))
                + trimmed.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    /** Line 2 for a saved chat: Heute / Gestern / the date. */
    private static String savedMeta(ChatRecord record, long startOfToday) {
        if (record == null) {
            return "";
        }
        long at = record.getModifiedAt();
        if (at >= startOfToday) {
            return "Heute";
        }
        if (at >= startOfToday - DAY_MS) {
            return "Gestern";
        }
        return new java.text.SimpleDateFormat("dd.MM.yyyy").format(new java.util.Date(at));
    }

    /** The right-aligned time: HH:mm for today/yesterday (older rows carry the date in line 2). */
    private static String rowTime(ChatRecord record, long startOfToday) {
        if (record == null) {
            return "";
        }
        long at = record.getModifiedAt();
        if (at >= startOfToday - DAY_MS) {
            return new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date(at));
        }
        return "";
    }

    private void openAssignProjectDialog(ChatSessionId openId, ChatRecord record) {
        java.util.Set<String> known = new java.util.TreeSet<String>();
        if (historyStore != null) {
            for (ChatRecord other : historyStore.list()) {
                if (other.getProject() != null) {
                    known.add(other.getProject());
                }
            }
        }
        javax.swing.JComboBox<String> combo =
                new javax.swing.JComboBox<String>(known.toArray(new String[0]));
        combo.setEditable(true); // free naming creates a new project on the spot
        combo.setSelectedItem(record.getProject() == null ? "" : record.getProject());
        int choice = JOptionPane.showConfirmDialog(this, combo, "Assign chat to project",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice == JOptionPane.OK_OPTION) {
            Object typed = combo.getEditor().getItem();
            applyProject(openId, record, typed == null ? null : typed.toString());
        }
    }

    /**
     * Persist the assignment. An OPEN chat owns its live record (the panel autosaves it), so the
     * change must go through the panel — a store-side write on a loaded copy would be overwritten.
     */
    private void applyProject(ChatSessionId openId, ChatRecord record, String project) {
        ChatSessionComponent session = openId == null ? null : sessionsById.get(openId);
        if (session instanceof OllamaChatPanel) {
            ((OllamaChatPanel) session).setChatProject(project);
        } else if (historyStore != null) {
            record.setProject(project);
            historyStore.save(record);
        }
        refreshChatList();
    }

    /** Delete EVERY saved chat — only after an explicit confirmation. Open chats stay open. */
    private void deleteAllChats() {
        List<ChatRecord> chats = historyStore != null ? historyStore.list()
                : java.util.Collections.<ChatRecord>emptyList();
        if (chats.isEmpty()) {
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                "Delete ALL " + chats.size() + " saved chats? This cannot be undone.",
                "Delete all chats", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        for (ChatRecord chat : chats) {
            historyStore.delete(chat.getId());
            detachOpenPanelFromDeletedChat(chat.getId());
        }
        refreshChatList();
    }

    /** Stop an open chat panel from re-saving its just-deleted persisted record. */
    private void detachOpenPanelFromDeletedChat(String chatId) {
        for (Map.Entry<ChatSessionId, ChatSessionComponent> entry : sessionsById.entrySet()) {
            if (entry.getKey().toString().equals(chatId)
                    && entry.getValue() instanceof OllamaChatPanel) {
                ((OllamaChatPanel) entry.getValue()).detachFromPersistedChat();
            }
        }
    }

    // ------------------------------------------------------------------ listener plumbing

    private void fireTabSetChanged() {
        if (tabSetListener != null) {
            tabSetListener.tabSetChanged(new ArrayList<ChatSessionId>(sessionsById.keySet()));
        }
    }

    private void fireActiveSessionChanged() {
        if (activeSessionListener == null) {
            return;
        }
        ChatSessionComponent active = activeSession();
        ChatSessionId id = active == null ? null : active.getSessionId();
        if (id == null || id.equals(lastNotifiedSessionId)) {
            return;
        }
        lastNotifiedSessionId = id;
        activeSessionListener.activeSessionChanged(id);
    }

    // ------------------------------------------------------------------ test accessors (package-private)

    int openSessionCount() {
        return sessionsById.size();
    }

    boolean hasSession(ChatSessionId id) {
        return sessionsById.containsKey(id);
    }

    List<ChatSessionId> openSessionIds() {
        return new ArrayList<ChatSessionId>(sessionsById.keySet());
    }

    ComicSplitPane sidebarSplitForTest() {
        return sidebarSplit;
    }

    ChatSidebarPanel sidebarForTest() {
        return sidebar;
    }

    JButton burgerForTest() {
        return burger;
    }

    /** Runs the debounced width save immediately — tests must not wait on the timer. */
    void flushSidebarWidthSaveForTest() {
        saveSidebarWidthNow();
    }

    com.aresstack.comiccontrols.control.ComicSearchBar chatFilterForTest() {
        return chatFilter;
    }

    /** The visible chat-list texts in order (group/project headers, row titles) — for tests. */
    java.util.List<String> chatListEntriesForTest() {
        refreshChatList();
        java.util.List<String> texts = new ArrayList<String>();
        for (java.awt.Component component : chatListPanel.getComponents()) {
            if (component instanceof ChatHistoryRow) {
                texts.add(((ChatHistoryRow) component).titleForTest());
            } else if (component instanceof JLabel) {
                texts.add(((JLabel) component).getText());
            }
        }
        return texts;
    }
}
