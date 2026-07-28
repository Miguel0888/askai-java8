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

    static final String MODE_FAKE_LABEL = "Fake (clickdummy / development)";
    static final String MODE_ACP_LABEL = "Productive (ACP + browser sidecar)";

    private final WorkspaceStateStore store;
    private final JComboBox<String> mode = new JComboBox<String>(
            new String[]{MODE_FAKE_LABEL, MODE_ACP_LABEL});
    // Deliberately NO agent-Java field: the agent is Java-8 bytecode and simply runs on AskAI's own
    // JVM. A persisted override (store key) stays possible for special cases, but it is not a user
    // decision — the ONE configurable runtime is the Java >= 21 for the browser sidecar (GraalJS),
    // and even that is auto-discovered (or unnecessary when AskAI itself runs on >= 21).
    private String agentJavaOverride = "";
    private final JTextField agentJar = new JTextField(38);
    private final JTextField sidecarJava = new JTextField(38);
    private final JTextField sidecarJar = new JTextField(38);
    private final JComboBox<String> browserChannel = new JComboBox<String>(new String[]{"chrome", "msedge"});
    private final JCheckBox headless = new JCheckBox("Run the browser headless", true);
    private final JTextField searchUrl = new JTextField(38);
    private final JCheckBox allowPrivate = new JCheckBox(
            "Allow private/loopback targets (development only)", false);
    private final JButton save = new JButton("Save");
    private final JButton check = new JButton("Check requirements");
    private final JLabel busy = new JLabel(" ");
    private final JTextArea results = new JTextArea(8, 48);

    public ResearchRuntimeSettingsPanel(WorkspaceStateStore store) {
        super(new BorderLayout(8, 8));
        this.store = store;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(row("Backend mode:", mode));
        form.add(pathRow("Research agent jar:", agentJar));
        form.add(pathRow("Java for browser (≥21):", sidecarJava));
        form.add(pathRow("Browser sidecar jar:", sidecarJar));
        form.add(row("Browser channel:", browserChannel));
        form.add(row("", headless));
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
        return new ResearchRuntimeSettings(
                mode.getSelectedIndex() == 1 ? ResearchBackendMode.ACP : ResearchBackendMode.FAKE,
                agentJavaOverride, agentJar.getText(), sidecarJava.getText(), sidecarJar.getText(),
                String.valueOf(browserChannel.getSelectedItem()), headless.isSelected(),
                searchUrl.getText(), allowPrivate.isSelected());
    }

    private void apply(ResearchRuntimeSettings settings) {
        mode.setSelectedIndex(settings.getMode() == ResearchBackendMode.ACP ? 1 : 0);
        agentJavaOverride = settings.getAgentJavaExecutable();
        agentJar.setText(settings.getAgentJar());
        sidecarJava.setText(settings.getSidecarJavaExecutable());
        sidecarJar.setText(settings.getSidecarJar());
        browserChannel.setSelectedItem(settings.getBrowserChannel());
        headless.setSelected(settings.isHeadless());
        searchUrl.setText(settings.getSearchUrlTemplate());
        allowPrivate.setSelected(settings.isAllowPrivateNetworks());
    }

    private void saveSettings() {
        ResearchRuntimeSettings settings = currentSettings();
        if (settings.getMode() == ResearchBackendMode.ACP) {
            List<String> problems = settings.validateProductive();
            if (!problems.isEmpty()) {
                StringBuilder sb = new StringBuilder("Not saved — the productive configuration is not usable:\n");
                for (String problem : problems) {
                    sb.append("  - ").append(problem).append('\n');
                }
                results.setText(sb.toString());
                return;
            }
        }
        settings.save(store);
        results.setText("Saved. Mode: " + (settings.getMode() == ResearchBackendMode.ACP
                ? MODE_ACP_LABEL : MODE_FAKE_LABEL)
                + "\n\nIMPORTANT: the RUNNING session keeps its current backend."
                + "\nClose this Research session and open a new one to start with this configuration.");
    }

    private void runCheck() {
        final ResearchRuntimeSettings settings = currentSettings();
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
}
