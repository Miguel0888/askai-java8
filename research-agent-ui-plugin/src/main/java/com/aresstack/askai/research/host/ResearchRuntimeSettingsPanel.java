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
    /** Bot-control MCP (default ON): run_command/session_state/chat_history + service-endpoint.json. */
    private final JCheckBox botControlMcp = new JCheckBox(
            "Bot control via MCP (applies to new sessions)", true);
    /** THIS session's live connection line: transport + URL (token in the path), or why there is none. */
    private final JLabel botConnection = new JLabel(" ");
    /** Square, margin-less icon button: fetches THIS session's tool list ON DEMAND from the registry. */
    private final javax.swing.JButton botTools = new javax.swing.JButton("\u2630");
    /** The live session behind this settings page (nullable: store-only construction in tests). */
    private final com.aresstack.askai.research.agent.ResearchAgentSession session;
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
    private final JTextField searchUrl = new JTextField(38);
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
        form.add(row("", botControlMcp));
        // The LIVE connection of THIS session: Streamable HTTP (MCP JSON-RPC over loopback), token in the
        // URL path — plus the square on-demand tools button (never a hardcoded list).
        botTools.setMargin(new java.awt.Insets(0, 0, 0, 0));
        java.awt.Dimension square = new java.awt.Dimension(24, 24);
        botTools.setPreferredSize(square);
        botTools.setMinimumSize(square);
        botTools.setMaximumSize(square);
        botTools.setToolTipText("List the endpoint's tools (fetched live)");
        botTools.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                showBotTools();
            }
        });
        JPanel botRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        botRow.setOpaque(false);
        botRow.add(botTools);
        botRow.add(botConnection);
        form.add(row("Bot MCP:", botRow));
        refreshBotConnection();
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
        form.add(row("Search URL ({query}):", searchUrl));
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
        llmNarration.setSelected(ResearchRuntimeSettings.loadLlmNarration(store));
        llmNarration.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                // Like the language: persisted immediately; running sessions keep their narrator.
                ResearchRuntimeSettings.saveLlmNarration(ResearchRuntimeSettingsPanel.this.store,
                        llmNarration.isSelected());
            }
        });
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
                searchUrl.getText(), allowPrivate.isSelected(),
                carriedRerankerModel);
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
        // Visible, editable default so the productive mode is saveable without typing — the loop
        // always starts with web_search, so an empty provider would only produce a validation error.
        searchUrl.setText(settings.getSearchUrlTemplate().isEmpty()
                ? "https://www.bing.com/search?q={query}" : settings.getSearchUrlTemplate());
        allowPrivate.setSelected(settings.isAllowPrivateNetworks());
    }

    private void saveSettings() {
        ResearchRuntimeSettings settings = currentSettings();
        // Always save — configuration is never rejected; what NEW sessions do is computed from it.
        settings.save(store);
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
    /** The live connection line of THIS session (re-evaluated on open; the endpoint lives per session). */
    private void refreshBotConnection() {
        com.aresstack.askai.research.mcp.ResearchBotControlEndpoint endpoint =
                session == null ? null : session.botControlEndpoint();
        String url = endpoint == null ? null : endpoint.connectionUrl();
        if (url == null) {
            botConnection.setText("off for this session (transport: Streamable HTTP / MCP JSON-RPC, "
                    + "loopback only; connection data in <project>/service-endpoint.json)");
            botTools.setEnabled(false);
            return;
        }
        botConnection.setText("Streamable HTTP (MCP JSON-RPC, token in the URL path): " + url);
        botTools.setEnabled(true);
    }

    /** Fetch the CURRENT tool list from the live registry and show it — nothing is hardcoded here. */
    private void showBotTools() {
        com.aresstack.askai.research.mcp.ResearchBotControlEndpoint endpoint =
                session == null ? null : session.botControlEndpoint();
        if (endpoint == null) {
            return;
        }
        StringBuilder sb = new StringBuilder("Endpoint: ").append(endpoint.getEndpointId())
                .append("\nTransport: Streamable HTTP (MCP JSON-RPC)\nURL: ")
                .append(endpoint.connectionUrl()).append("\n\nTools (live):\n");
        for (String name : endpoint.toolNames()) {
            sb.append("  - ").append(name).append('\n');
        }
        javax.swing.JTextArea text = new javax.swing.JTextArea(sb.toString(), 10, 60);
        text.setEditable(false);
        javax.swing.JOptionPane.showMessageDialog(this, new javax.swing.JScrollPane(text),
                "Bot control MCP", javax.swing.JOptionPane.INFORMATION_MESSAGE);
    }
}