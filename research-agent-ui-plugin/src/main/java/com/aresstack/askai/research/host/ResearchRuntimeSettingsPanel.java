package com.aresstack.askai.research.host;

import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.acp.ResearchBackendMode;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;

/**
 * The research runtime configuration surface. Reads/writes exclusively through
 * {@link ResearchRuntimeSettings} (the same mapper the session factory uses). Saving an ACP configuration
 * that fails validation is rejected with the concrete problem list; FAKE is labelled clearly as the
 * clickdummy/development mode. "Check requirements" runs validation + the sidecar readiness probe on a
 * background daemon thread (never the EDT) with a visible busy state; results come back via
 * {@link SwingUtilities#invokeLater} as one structured line per requirement.
 */
public final class ResearchRuntimeSettingsPanel extends JPanel {

    private final WorkspaceStateStore store;
    // There is deliberately NO backend-mode field: productive is simply THE mode whenever its
    // requirements are met; otherwise new sessions run the demo backend with a visible notice.
    // (A persisted FAKE value remains a developer-only override in the store.)
    private final JLabel backendStatus = new JLabel(" ");
    /** DEFAULT language for NEW research sessions (English default, German translation) — persisted. */
    private final JComboBox<String> agentLanguage =
            new JComboBox<String>(new String[]{"English", "Deutsch"});
    /** LLM narration (default off): milestone texts phrased by the main model, validated, with fallback. */
    private final JCheckBox llmNarration = new JCheckBox(
            "AI-phrased guidance (uses the main model; applies to new sessions)", false);
    /** Scoping orientation tags (default off = long-standing behaviour): suggestions on every broad turn. */
    private final JCheckBox alwaysSuggest = new JCheckBox(
            "Immer Suchvorschläge anbieten (Orientierungs-Tags; applies to new sessions)", false);
    /** Answer budget per agent model turn (tokens) — the longest contracted answer is the source review. */
    private final JTextField agentMaxTokens = new JTextField(6);
    // Search-run limits: the completion target + the safety limits. Every bound is a setting.
    private final JTextField searchTargetSources = new JTextField(4);
    private final JTextField searchMaxPages = new JTextField(4);
    private final JTextField searchMaxToolCalls = new JTextField(4);
    private final JTextField searchMaxMinutes = new JTextField(4);
    private final JTextField browserToolTimeout = new JTextField(4);
    private final JTextField searchMaxErrors = new JTextField(4);
    // Review-context bounds: how many sources one review reads, and how much of each.
    private final JTextField reviewMaxSources = new JTextField(4);
    private final JTextField reviewMaxChars = new JTextField(6);
    /** Bot-control MCP (default ON): run_command/session_state/chat_history + service-endpoint.json. */
    private final JCheckBox botControlMcp = new JCheckBox(
            "Bot control via MCP (applies to new sessions)", true);
    /** Square, margin-less info button (Unicode \u24D8): opens the live connection/tool details. */
    private final javax.swing.JButton botTools = new javax.swing.JButton("\u24D8");
    /** The live session behind this settings page (nullable: store-only construction in tests). */
    private final com.aresstack.askai.research.agent.ResearchAgentSession session;
    /** ChatGPT connector (default OFF): AskAI as its own public MCP+OAuth face behind the TLS proxy. */
    private final JCheckBox chatGptConnector = new JCheckBox(
            "ChatGPT connector (public endpoint behind the reverse proxy; applies to new sessions)", false);
    private final JTextField connectorOrigin = placeholderField("https://askai.example.com", 38);
    private final JTextField connectorPort = new JTextField(6);
    private final JTextField connectorClientId =
            placeholderField(com.aresstack.askai.research.connector.ConnectorConfig.DEFAULT_CLIENT_ID, 12);
    private final javax.swing.JPasswordField connectorClientSecret = placeholderPasswordField(
            com.aresstack.askai.research.connector.ConnectorConfig.DEFAULT_CLIENT_SECRET, 18);
    /**
     * Initial-search source (applies to new sessions): the legacy browser SERP (default) or one of the
     * REST search providers. Provider credentials are NOT configured here — they live in
     * {@code ${user.home}/agents/research/providers/} (brave.json, brightdata.json, dataforseo.json).
     */
    // Deliberately NO agent-Java field: the agent is Java-8 bytecode and simply runs on AskAI's own
    // JVM. A persisted override (store key) stays possible for special cases, but it is not a user
    // decision — the ONE configurable runtime is the Java >= 21 for the browser sidecar (GraalJS),
    // and even that is auto-discovered (or unnecessary when AskAI itself runs on >= 21).
    private String agentJavaOverride = "";
    private final JTextField agentJar = new JTextField(38);
    private final JTextField sidecarJava = new JTextField(38);
    private final JTextField sidecarJar = new JTextField(38);
    private final JComboBox<String> browserChannel = new JComboBox<String>(new String[]{"chrome", "msedge"});
    // The reranker model is chosen centrally in AskAI (Configuration → AI models), NOT here. Any previously
    // persisted plugin-side selection is carried through unchanged so it is never lost on save.
    private String carriedRerankerModel = "";
    private final JCheckBox headless = new JCheckBox("Run the browser headless", true);
    private final JCheckBox allowPrivate = new JCheckBox(
            "Allow private/loopback targets (development only)", false);
    private final JButton save = new JButton("Save");
    private final JButton check = new JButton("Check requirements");
    private final JLabel busy = new JLabel(" ");
    private final JTextArea results = new JTextArea(8, 48);

    public ResearchRuntimeSettingsPanel(WorkspaceStateStore store) {
        this(store, null);
    }

    public ResearchRuntimeSettingsPanel(WorkspaceStateStore store,
            com.aresstack.askai.research.agent.ResearchAgentSession session) {
        super(new BorderLayout(8, 8));
        this.session = session;
        this.store = store;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(row("Backend:", backendStatus));
        form.add(row("Language / Sprache:", agentLanguage));
        form.add(row("", llmNarration));
        form.add(row("", alwaysSuggest));
        form.add(row("Agent-Antwortbudget (Tokens):", agentMaxTokens));
        JPanel searchLimits = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        searchLimits.setOpaque(false);
        searchLimits.add(labelled("Quellenziel", searchTargetSources));
        searchLimits.add(javax.swing.Box.createHorizontalStrut(10));
        searchLimits.add(labelled("Seiten", searchMaxPages));
        searchLimits.add(javax.swing.Box.createHorizontalStrut(10));
        searchLimits.add(labelled("Tool-Calls", searchMaxToolCalls));
        searchLimits.add(javax.swing.Box.createHorizontalStrut(10));
        searchLimits.add(labelled("Minuten", searchMaxMinutes));
        searchLimits.add(labelled("Tool-Timeout (s)", browserToolTimeout));
        searchLimits.add(javax.swing.Box.createHorizontalStrut(10));
        searchLimits.add(labelled("Fehler", searchMaxErrors));
        form.add(row("Such-Limits:", searchLimits));
        JPanel reviewLimits = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        reviewLimits.setOpaque(false);
        reviewLimits.add(labelled("Quellen", reviewMaxSources));
        reviewLimits.add(javax.swing.Box.createHorizontalStrut(10));
        reviewLimits.add(labelled("Zeichen/Quelle", reviewMaxChars));
        form.add(row("Review-Kontext:", reviewLimits));
        botTools.setMargin(new java.awt.Insets(0, 0, 0, 0));
        botTools.setPreferredSize(new java.awt.Dimension(24, 24));
        botTools.setFont(botTools.getFont().deriveFont(java.awt.Font.PLAIN, 15f));
        botTools.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                showBotTools();
            }
        });
        com.aresstack.askai.research.mcp.ResearchBotControlEndpoint liveEndpoint =
                session == null ? null : session.botControlEndpoint();
        botTools.setEnabled(liveEndpoint != null);
        botTools.setToolTipText(liveEndpoint != null
                ? "Streamable HTTP (MCP JSON-RPC): " + liveEndpoint.connectionUrl()
                : "Diese Session hat keinen Bot-Endpoint (deaktiviert oder keine produktive Session)");
        JPanel botRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        botRow.setOpaque(false);
        botRow.add(botControlMcp);
        botRow.add(javax.swing.Box.createHorizontalStrut(6));
        botRow.add(botTools);
        form.add(row("", botRow));
        form.add(row("", chatGptConnector));
        form.add(row("Public origin:", connectorOrigin));
        JPanel connectorDetails = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        connectorDetails.setOpaque(false);
        connectorDetails.add(connectorPort);
        connectorDetails.add(javax.swing.Box.createHorizontalStrut(12));
        connectorDetails.add(new JLabel("Client-ID:"));
        connectorDetails.add(javax.swing.Box.createHorizontalStrut(4));
        connectorDetails.add(connectorClientId);
        connectorDetails.add(javax.swing.Box.createHorizontalStrut(12));
        connectorDetails.add(new JLabel("Secret:"));
        connectorDetails.add(javax.swing.Box.createHorizontalStrut(4));
        connectorDetails.add(connectorClientSecret);
        form.add(row("Connector port:", connectorDetails));
        // The initial-search PROVIDER selection + its provider-specific settings live on their own
        // "Search" gear tab (SearchProviderCardsPanel), not as shared fields here.
        form.add(pathRow("Research agent jar:", agentJar));
        form.add(pathRow("Java for browser (≥21):", sidecarJava));
        form.add(pathRow("Browser sidecar jar:", sidecarJar));
        form.add(row("Browser channel:", browserChannel));
        form.add(row("", headless));
        // Transparency does not depend on a visible browser window: the chat shows the visited sites.
        JLabel headlessHint = new JLabel(new com.aresstack.askai.research.agent.ResearchPlaybook(
                com.aresstack.askai.research.agent.ResearchLanguage.fromCode(
                        ResearchRuntimeSettings.loadLanguage(store))).headlessHint());
        headlessHint.setEnabled(false);
        form.add(row("", headlessHint));
        form.add(row("", allowPrivate));
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(save);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(check);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(busy);
        form.add(buttons);
        add(form, BorderLayout.NORTH);

        results.setEditable(false);
        results.setLineWrap(false);
        add(new JScrollPane(results), BorderLayout.CENTER);

        // Persisted values first, then AUTOMATIC defaults for whatever is still empty (assembled
        // distribution, discovered Java >= 21) — no empty mandatory fields on a normal dev start.
        // The agent-Java override is kept ONLY when the user persisted one explicitly; the automatic
        // value (AskAI's own JVM) is applied by the factory and never written back as a setting.
        ResearchRuntimeSettings persisted = ResearchRuntimeSettings.load(store);
        apply(ResearchRuntimeDefaults.complete(persisted));
        agentJavaOverride = persisted.getAgentJavaExecutable();
        // The combo persists the DEFAULT language for NEW research sessions; running sessions keep
        // their own session-local language (live-switchable at the session, never globally from here).
        String languageCode = ResearchRuntimeSettings.loadLanguage(store);
        agentLanguage.setSelectedIndex("de".equals(languageCode) ? 1 : 0);
        agentLanguage.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                String code = agentLanguage.getSelectedIndex() == 1 ? "de" : "en";
                ResearchRuntimeSettings.saveLanguage(ResearchRuntimeSettingsPanel.this.store, code);
            }
        });
        botControlMcp.setSelected(ResearchRuntimeSettings.loadBotControlMcp(store));
        botControlMcp.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                // Persisted immediately; running sessions keep their endpoint until they close.
                ResearchRuntimeSettings.saveBotControlMcp(ResearchRuntimeSettingsPanel.this.store,
                        botControlMcp.isSelected());
            }
        });
        ResearchRuntimeSettings.ChatGptConnectorSettings connector =
                ResearchRuntimeSettings.loadChatGptConnectorSettings(store);
        chatGptConnector.setSelected(connector.isEnabled());
        connectorOrigin.setText(connector.getPublicOrigin());
        connectorOrigin.setToolTipText(
                "The public HTTPS origin the Apache proxy serves, e.g. https://askai.example.com");
        connectorPort.setText(String.valueOf(connector.getPort()));
        connectorPort.setToolTipText("Local plain-HTTP listen port; the proxy machine forwards to it");
        connectorClientId.setText(com.aresstack.askai.research.connector.ConnectorConfig
                .DEFAULT_CLIENT_ID.equals(connector.getClientId()) ? "" : connector.getClientId());
        connectorClientSecret.setText(com.aresstack.askai.research.connector.ConnectorConfig
                .DEFAULT_CLIENT_SECRET.equals(connector.getClientSecret()) ? ""
                : connector.getClientSecret());
        chatGptConnector.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                persistConnectorSettings();
            }
        });
        llmNarration.setSelected(ResearchRuntimeSettings.loadLlmNarration(store));
        llmNarration.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                // Like the language: persisted immediately; running sessions keep their narrator.
                ResearchRuntimeSettings.saveLlmNarration(ResearchRuntimeSettingsPanel.this.store,
                        llmNarration.isSelected());
            }
        });
        alwaysSuggest.setSelected(ResearchRuntimeSettings.loadAlwaysOfferSearchSuggestions(store));
        alwaysSuggest.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                // Persisted immediately; the flag reaches the agent at the NEXT session launch.
                ResearchRuntimeSettings.saveAlwaysOfferSearchSuggestions(
                        ResearchRuntimeSettingsPanel.this.store, alwaysSuggest.isSelected());
            }
        });
        agentMaxTokens.setText(String.valueOf(ResearchRuntimeSettings.loadAgentMaxOutputTokens(store)));
        agentMaxTokens.setToolTipText("Antwort-Budget des Agenten in Tokens pro Modell-Turn (die längste "
                + "Antwort ist die Quellen-Auswertung). Ein zu kleiner Wert schneidet Antworten ab. "
                + "Gilt für neue Sessions.");
        agentMaxTokens.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                persistAgentMaxTokens();
            }
        });
        agentMaxTokens.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent event) {
                persistAgentMaxTokens();
            }
        });
        bindLimit(searchTargetSources, ResearchRuntimeSettings.KEY_SEARCH_TARGET_SOURCES,
                ResearchRuntimeSettings.DEFAULT_SEARCH_TARGET_SOURCES,
                "Die Suche endet regulär bei so vielen akzeptierten Quellen (gilt für neue Sessions).");
        bindLimit(searchMaxPages, ResearchRuntimeSettings.KEY_SEARCH_MAX_PAGES,
                ResearchRuntimeSettings.DEFAULT_SEARCH_MAX_PAGES,
                "Sicherheitslimit: maximal besuchte Seiten pro Suchlauf.");
        bindLimit(searchMaxToolCalls, ResearchRuntimeSettings.KEY_SEARCH_MAX_TOOL_CALLS,
                ResearchRuntimeSettings.DEFAULT_SEARCH_MAX_TOOL_CALLS,
                "Sicherheitslimit: maximale Tool-Aufrufe pro Suchlauf.");
        bindLimit(searchMaxMinutes, ResearchRuntimeSettings.KEY_SEARCH_MAX_MINUTES,
                ResearchRuntimeSettings.DEFAULT_SEARCH_MAX_MINUTES,
                "Sicherheitslimit (Timeout): maximale Laufzeit in Minuten; Wartezeiten auf den "
                        + "Nutzer zählen nicht.");
        bindLimit(searchMaxErrors, ResearchRuntimeSettings.KEY_SEARCH_MAX_ERRORS,
                ResearchRuntimeSettings.DEFAULT_SEARCH_MAX_ERRORS,
                "Sicherheitslimit: maximale AUFEINANDERFOLGENDE technische Fehler, bevor der Lauf endet.");
        bindLimit(browserToolTimeout, ResearchRuntimeSettings.KEY_BROWSER_TOOL_TIMEOUT_SECONDS,
                ResearchRuntimeSettings.DEFAULT_BROWSER_TOOL_TIMEOUT_SECONDS,
                "Timeout je Browser-Aufruf in Sekunden. Muss die komplette Suchmaschinen-Phase "
                        + "abdecken (mehrere Engines und Ergebnisseiten, sequenziell mit Auswertung) — "
                        + "zu klein wird als 'Sidecar tot' fehlgedeutet und bricht jede Suche ab.");
        bindLimit(reviewMaxSources, ResearchRuntimeSettings.KEY_REVIEW_MAX_SOURCES,
                ResearchRuntimeSettings.DEFAULT_REVIEW_MAX_SOURCES,
                "Wie viele Quellen eine Auswertung höchstens liest (neueste zuerst).");
        bindLimit(reviewMaxChars, ResearchRuntimeSettings.KEY_REVIEW_MAX_CHARS,
                ResearchRuntimeSettings.DEFAULT_REVIEW_MAX_CHARS,
                "Wie viele Zeichen je Quelle in die Auswertung gelangen.");
        refreshBackendStatus();

        save.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                saveSettings();
            }
        });
        check.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                runCheck();
            }
        });
    }

    /** The panel state as the SAME typed model the factory reads — no second mapping. */
    ResearchRuntimeSettings currentSettings() {
        // Saving from the panel always persists the automatic mode (this also migrates stores where an
        // old default once wrote FAKE); the demo backend is chosen by the FACTORY only when the
        // requirements are not met.
        return new ResearchRuntimeSettings(ResearchBackendMode.ACP,
                agentJavaOverride, agentJar.getText(), sidecarJava.getText(), sidecarJar.getText(),
                String.valueOf(browserChannel.getSelectedItem()), headless.isSelected(),
                allowPrivate.isSelected(), carriedRerankerModel);
    }

    /** The live projection of what NEW sessions will do — computed, never chosen. */
    private void refreshBackendStatus() {
        List<String> problems = ResearchRuntimeDefaults.complete(currentSettings()).validateProductive();
        backendStatus.setText(problems.isEmpty()
                ? "Productive (requirements met — new sessions research with the real browser)"
                : "DEMO (missing: " + problems.get(0)
                        + (problems.size() > 1 ? " +" + (problems.size() - 1) + " more" : "") + ")");
    }

    private void apply(ResearchRuntimeSettings settings) {
        agentJavaOverride = settings.getAgentJavaExecutable();
        agentJar.setText(settings.getAgentJar());
        sidecarJava.setText(settings.getSidecarJavaExecutable());
        sidecarJar.setText(settings.getSidecarJar());
        browserChannel.setSelectedItem(settings.getBrowserChannel());
        // Carry any previously persisted reranker selection through invisibly (chosen centrally now).
        carriedRerankerModel = settings.getSelectedRerankerModel();
        headless.setSelected(settings.isHeadless());
        allowPrivate.setSelected(settings.isAllowPrivateNetworks());
    }

    /** A text field with a gray in-field placeholder, shown while the field is empty. */
    private static JTextField placeholderField(final String placeholder, int columns) {
        final JTextField field = new JTextField(columns) {
            protected void paintComponent(java.awt.Graphics g) {
                super.paintComponent(g);
                if (getText().isEmpty()) {
                    java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                            java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(java.awt.Color.GRAY);
                    g2.setFont(getFont());
                    g2.drawString(placeholder, getInsets().left + 2,
                            getBaseline(getWidth(), getHeight()));
                    g2.dispose();
                }
            }
        };
        return field;
    }

    /** A password field with a gray in-field placeholder (the effective default), shown while empty. */
    private static javax.swing.JPasswordField placeholderPasswordField(final String placeholder,
                                                                       int columns) {
        return new javax.swing.JPasswordField(columns) {
            protected void paintComponent(java.awt.Graphics g) {
                super.paintComponent(g);
                if (getPassword().length == 0) {
                    java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                            java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(java.awt.Color.GRAY);
                    g2.setFont(getFont());
                    g2.drawString(placeholder, getInsets().left + 2,
                            getBaseline(getWidth(), getHeight()));
                    g2.dispose();
                }
            }
        };
    }

    /** A tiny "Label: [field]" group for compact multi-field limit rows. */
    private static JPanel labelled(String label, JTextField field) {
        JPanel group = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        group.setOpaque(false);
        group.add(new JLabel(label + ": "));
        group.add(field);
        return group;
    }

    /**
     * Bind one positive-integer limit field to its persisted setting: initial value from the store,
     * persisted on Enter and focus-lost, and a non-numeric/non-positive entry resets to the persisted
     * value instead of silently storing something broken. Applies to NEW sessions like its neighbours.
     */
    private void bindLimit(final JTextField field, final String key, final int fallback, String tooltip) {
        field.setText(String.valueOf(
                ResearchRuntimeSettings.loadPositiveInt(store, key, fallback)));
        field.setToolTipText(tooltip);
        final Runnable persist = new Runnable() {
            public void run() {
                try {
                    int value = Integer.parseInt(field.getText().trim());
                    if (value > 0) {
                        ResearchRuntimeSettings.savePositiveInt(store, key, value);
                        return;
                    }
                } catch (NumberFormatException invalid) {
                    // fall through to the reset below
                }
                field.setText(String.valueOf(
                        ResearchRuntimeSettings.loadPositiveInt(store, key, fallback)));
            }
        };
        field.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                persist.run();
            }
        });
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent event) {
                persist.run();
            }
        });
    }

    /** Persist the agent answer budget; a non-numeric/non-positive entry resets to the persisted value. */
    private void persistAgentMaxTokens() {
        try {
            int tokens = Integer.parseInt(agentMaxTokens.getText().trim());
            if (tokens > 0) {
                ResearchRuntimeSettings.saveAgentMaxOutputTokens(store, tokens);
                return;
            }
        } catch (NumberFormatException invalid) {
            // fall through to the reset below
        }
        agentMaxTokens.setText(String.valueOf(ResearchRuntimeSettings.loadAgentMaxOutputTokens(store)));
    }

    /** All five connector controls as ONE persisted unit (from the toggle and from Save). */
    private void persistConnectorSettings() {
        int port;
        try {
            port = Integer.parseInt(connectorPort.getText().trim());
        } catch (NumberFormatException invalid) {
            port = 8082;
        }
        // The secret is OPTIONAL: empty = public client (PKCE only) — exactly what ChatGPT's own
        // dynamic client registration expects. A non-empty secret is enforced on the token endpoint.
        String secret = new String(connectorClientSecret.getPassword()).trim();
        ResearchRuntimeSettings.ChatGptConnectorSettings settings =
                new ResearchRuntimeSettings.ChatGptConnectorSettings(
                        chatGptConnector.isSelected(), port, connectorOrigin.getText().trim(),
                        connectorClientId.getText().trim(), secret);
        ResearchRuntimeSettings.saveChatGptConnectorSettings(store, settings);
        // The listener follows the setting IMMEDIATELY — no new session required. It serves whatever is
        // in the session directory; OFF stops only the listener, the running sessions stay registered.
        com.aresstack.askai.research.connector.ChatGptConnectorRuntime runtime =
                com.aresstack.askai.research.connector.ChatGptConnectorRuntime.get();
        if (settings.isEnabled()) {
            runtime.ensureStarted(new com.aresstack.askai.research.connector.ConnectorConfig(
                    settings.getPort(), settings.getPublicOrigin(), settings.getClientId(),
                    settings.getClientSecret(),
                    com.aresstack.askai.research.connector.ChatGptConnectorRuntime
                            .defaultRefreshStore()));
            String failure = runtime.lastStartFailure();
            results.setText(runtime.isRunning()
                    ? "ChatGPT connector: listening on port " + runtime.runningPort()
                    : "ChatGPT connector: NOT running - " + failure);
            if (!runtime.isRunning()) {
                javax.swing.JOptionPane.showMessageDialog(this,
                        "Der ChatGPT-Connector konnte nicht starten:\n" + failure,
                        "ChatGPT connector", javax.swing.JOptionPane.WARNING_MESSAGE);
            }
        } else {
            runtime.stopListener();
        }
    }

    private void saveSettings() {
        ResearchRuntimeSettings settings = currentSettings();
        // Always save — configuration is never rejected; what NEW sessions do is computed from it.
        settings.save(store);
        persistConnectorSettings();
        List<String> problems = ResearchRuntimeDefaults.complete(settings).validateProductive();
        refreshBackendStatus();
        StringBuilder sb = new StringBuilder("Saved.\n");
        if (problems.isEmpty()) {
            sb.append("New Research sessions run PRODUCTIVE (real browser research).\n");
        } else {
            sb.append("New Research sessions run in DEMO mode until these are fixed:\n");
            for (String problem : problems) {
                sb.append("  - ").append(problem).append('\n');
            }
        }
        sb.append("\nThe RUNNING session keeps its current backend — close this Research session and"
                + "\nopen a new one to apply.");
        results.setText(sb.toString());
    }

    private void runCheck() {
        final ResearchRuntimeSettings settings = ResearchRuntimeDefaults.complete(currentSettings());
        check.setEnabled(false);
        save.setEnabled(false);
        busy.setText("Checking requirements (starts the sidecar briefly)…");
        results.setText("");
        Thread worker = new Thread(new Runnable() {
            public void run() {
                final StringBuilder sb = new StringBuilder();
                try {
                    for (ResearchRuntimeCapabilityCheck.Item item
                            : ResearchRuntimeCapabilityCheck.run(settings)) {
                        sb.append(item.render()).append('\n');
                    }
                } catch (RuntimeException unexpected) {
                    sb.append("[FAIL] Requirement check failed — ").append(unexpected.getMessage());
                }
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        results.setText(sb.toString());
                        busy.setText(" ");
                        check.setEnabled(true);
                        save.setEnabled(true);
                    }
                });
            }
        }, "research-runtime-check");
        worker.setDaemon(true);
        worker.start();
    }

    /** A path field with a working Browse button (file chooser preset to the current value). */
    private JPanel pathRow(String label, final JTextField field) {
        JPanel row = row(label, field);
        JButton browse = new JButton("…");
        browse.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
                String current = field.getText().trim();
                if (!current.isEmpty()) {
                    chooser.setSelectedFile(new java.io.File(current));
                }
                if (chooser.showOpenDialog(ResearchRuntimeSettingsPanel.this)
                        == javax.swing.JFileChooser.APPROVE_OPTION) {
                    field.setText(chooser.getSelectedFile().getAbsolutePath());
                }
            }
        });
        row.add(browse);
        return row;
    }

    private static JPanel row(String label, javax.swing.JComponent field) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        JLabel jLabel = new JLabel(label);
        jLabel.setPreferredSize(new java.awt.Dimension(170, jLabel.getPreferredSize().height));
        row.add(jLabel);
        row.add(field);
        row.add(Box.createHorizontalGlue());
        return row;
    }
    /** The connection details + the live tool catalog — ONE compact block, uniformly formatted. */
    private void showBotTools() {
        com.aresstack.askai.research.mcp.ResearchBotControlEndpoint endpoint =
                session == null ? null : session.botControlEndpoint();
        if (endpoint == null) {
            return;
        }
        StringBuilder html = new StringBuilder("<html><body style='width:520px'>")
                .append("<b>Transport:</b> Streamable HTTP (MCP JSON-RPC), 127.0.0.1, "
                        + "Token im URL-Pfad<br>")
                .append("<b>Verbindungsdatei:</b> &lt;Projekt&gt;/service-endpoint.json "
                        + "(URL, Token, Usage-Guide)<br><br>")
                .append("<b>Tools (live):</b><br>");
        for (java.util.Map.Entry<String, String> tool : endpoint.toolCatalog().entrySet()) {
            html.append("<b>").append(tool.getKey()).append("</b> \u2014 ")
                .append(escapeHtml(tool.getValue())).append("<br><br>");
        }
        html.append("</body></html>");
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.add(copyableValue(endpoint.connectionUrl()), java.awt.BorderLayout.NORTH);
        content.add(new JLabel(html.toString()), java.awt.BorderLayout.CENTER);
        javax.swing.JOptionPane.showMessageDialog(this, content, "Bot control (MCP)",
                javax.swing.JOptionPane.PLAIN_MESSAGE);
    }

    private static String escapeHtml(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** A selectable, borderless field + copy button — the URL is DATA, not decoration. */
    private javax.swing.JComponent copyableValue(final String text) {
        JPanel value = new JPanel(new BorderLayout(6, 0));
        value.setOpaque(false);
        final javax.swing.JTextField field = new javax.swing.JTextField(text == null ? "" : text);
        field.setEditable(false);
        field.setBorder(null);
        field.setOpaque(false);
        field.setCaretPosition(0);
        value.add(field, java.awt.BorderLayout.CENTER);
        javax.swing.JButton copy = new javax.swing.JButton("Copy");
        copy.setMargin(new java.awt.Insets(0, 6, 0, 6));
        copy.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                        new java.awt.datatransfer.StringSelection(field.getText()), null);
            }
        });
        value.add(copy, java.awt.BorderLayout.EAST);
        return value;
    }

}