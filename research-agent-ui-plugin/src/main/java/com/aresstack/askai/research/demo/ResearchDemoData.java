package com.aresstack.askai.research.demo;

import com.aresstack.askai.research.domain.ResearchFinding;
import com.aresstack.askai.research.domain.ResearchOutline;
import com.aresstack.askai.research.domain.ResearchProblem;
import com.aresstack.askai.research.domain.ResearchSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Static, deterministic demo content for the clickdummy UI (Commit 7). It carries no timing and no backend —
 * the event-driven simulation arrives in Commit 8. The data is realistic enough to exercise the outline
 * tree, the section filters, the Markdown/Mermaid preview and the tables.
 */
public final class ResearchDemoData {

    private ResearchDemoData() {
    }

    public static ResearchOutline outline() {
        return ResearchOutline.empty()
                .addSection("", "s1", "1. Introduction")
                .addSection("", "s2", "2. Background")
                .addSection("s2", "s2a", "2.1 Prior work")
                .addSection("", "s3", "3. Architecture")
                .addSection("", "s4", "4. Conclusion");
    }

    public static List<ResearchSource> sources() {
        List<ResearchSource> sources = new ArrayList<ResearchSource>();
        sources.add(new ResearchSource("src1", "PF4J plugin lifecycle", "pf4j.org", 1000L, "Web",
                0.9, Arrays.asList("s3"), "Captured"));
        sources.add(new ResearchSource("src2", "Solon AI overview", "solon.noear.org", 1000L, "Web",
                0.85, Arrays.asList("s2", "s2a"), "Captured"));
        sources.add(new ResearchSource("src3", "ACP protocol notes", "internal", 1000L, "Doc",
                0.7, Arrays.asList("s3"), "Review"));
        return sources;
    }

    public static List<ResearchFinding> findings() {
        List<ResearchFinding> findings = new ArrayList<ResearchFinding>();
        findings.add(new ResearchFinding("f1", "PF4J isolates plugins per classloader.",
                Arrays.asList("src1"), Arrays.asList("s3"), 0.9, false, "Confirmed"));
        findings.add(new ResearchFinding("f2", "Solon AI runs on Java 8.",
                Arrays.asList("src2"), Arrays.asList("s2", "s2a"), 0.95, false, "Confirmed"));
        findings.add(new ResearchFinding("f3", "ACP session model still under review.",
                Arrays.asList("src3"), Arrays.asList("s3"), 0.5, true, "Open"));
        return findings;
    }

    public static List<ResearchProblem> problems() {
        List<ResearchProblem> problems = new ArrayList<ResearchProblem>();
        problems.add(new ResearchProblem("p1", ResearchProblem.Kind.OPEN_QUESTION,
                "Which ACP version to target?", "s3"));
        problems.add(new ResearchProblem("p2", ResearchProblem.Kind.CONTRADICTION,
                "Sources disagree on default timeouts.", "s3"));
        return problems;
    }

    /** A whole-document Markdown draft with a heading, list, table and a Mermaid diagram. */
    public static String documentMarkdown() {
        return "# Research draft\n\n"
                + "This is a **demo** document rendered through the host Markdown/Mermaid view.\n\n"
                + "## 3. Architecture\n\n"
                + "- Host + workspace plugins\n"
                + "- Agent inside Questing\n\n"
                + "| Component | Role |\n|---|---|\n| Host | shell |\n| Plugin | workspace |\n\n"
                + "```mermaid\ngraph TD\n  A[Host] --> B[Research Workspace]\n  B --> C[Agent]\n```\n";
    }

    public static String sectionMarkdown(String sectionTitle) {
        return "## " + sectionTitle + "\n\nDemo content for this section.\n";
    }
}
