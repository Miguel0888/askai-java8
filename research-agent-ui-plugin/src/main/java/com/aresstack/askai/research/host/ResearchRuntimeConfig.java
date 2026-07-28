package com.aresstack.askai.research.host;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Explicit configuration of the productive research runtime — every external path/setting is passed here,
 * never through hidden system properties or globals: the Java-8 launcher + jar of the external ACP research
 * agent, the Java-21 launcher + thin jar of the Playwright browser sidecar, the browser channel and the
 * search provider URL template. {@link #validate()} reports every missing prerequisite readably; a
 * generation switch validates BEFORE publishing (a broken config never displaces a working generation).
 */
public final class ResearchRuntimeConfig {

    private final String agentJavaExecutable;
    private final String agentJar;
    private final String sidecarJavaExecutable;
    private final String sidecarJar;
    private final String browserChannel;
    private final boolean headless;
    private final boolean allowPrivateNetworks;
    private final String searchUrlTemplate;

    public ResearchRuntimeConfig(String agentJavaExecutable, String agentJar,
                                 String sidecarJavaExecutable, String sidecarJar,
                                 String browserChannel, boolean headless,
                                 boolean allowPrivateNetworks, String searchUrlTemplate) {
        this.agentJavaExecutable = agentJavaExecutable;
        this.agentJar = agentJar;
        this.sidecarJavaExecutable = sidecarJavaExecutable;
        this.sidecarJar = sidecarJar;
        this.browserChannel = browserChannel == null || browserChannel.isEmpty() ? "chrome" : browserChannel;
        this.headless = headless;
        this.allowPrivateNetworks = allowPrivateNetworks;
        this.searchUrlTemplate = searchUrlTemplate;
    }

    public String getAgentJavaExecutable() { return agentJavaExecutable; }
    public String getAgentJar() { return agentJar; }
    public String getSidecarJavaExecutable() { return sidecarJavaExecutable; }
    public String getSidecarJar() { return sidecarJar; }
    public String getBrowserChannel() { return browserChannel; }
    public boolean isHeadless() { return headless; }
    public boolean isAllowPrivateNetworks() { return allowPrivateNetworks; }
    public String getSearchUrlTemplate() { return searchUrlTemplate; }

    /** @return all problems (empty = usable). Never throws; the CALLER decides (prepare vs. report). */
    public List<String> validate() {
        List<String> problems = new ArrayList<String>();
        requireFile(problems, "agent java executable", agentJavaExecutable);
        requireFile(problems, "agent jar", agentJar);
        requireFile(problems, "sidecar java executable (Java 21)", sidecarJavaExecutable);
        requireFile(problems, "sidecar jar", sidecarJar);
        if (searchUrlTemplate != null && !searchUrlTemplate.isEmpty()
                && !searchUrlTemplate.contains("{query}")) {
            problems.add("search url template must contain {query}");
        }
        return problems;
    }

    private static void requireFile(List<String> problems, String what, String path) {
        if (path == null || path.trim().isEmpty()) {
            problems.add(what + " is not configured");
        } else if (!new File(path).isFile()) {
            problems.add(what + " does not exist: " + path);
        }
    }
}
