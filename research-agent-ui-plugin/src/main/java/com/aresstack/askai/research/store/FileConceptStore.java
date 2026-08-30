package com.aresstack.askai.research.store;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * File-backed persistence for the KONZEPTPAPIER document — the JSON sibling of
 * {@link FileResearchBriefStore}, with the same two-level lifecycle but TWO revision notions:
 * the WORKING revision bumps on every successful micro-edit (concurrency + stale-branch
 * detection for the agent tools), the APPROVED revisions stay the user's explicit phase gate
 * ("Konzeptpapier freigeben & weiter"). Layout under the concept directory:
 * <pre>
 *   working.json / working.properties        (workingRevision, contentHash, updatedAt)
 *   revisions/0001.json / 0001.properties    (immutable, never overwritten)
 * </pre>
 * The document envelope is {@code {"title","subtitle","concept":[…]}} — {@code concept} is an
 * ARRAY on purpose: by the tree rule that makes the working surface structural while title and
 * subtitle are ValueLeafs that drop out of every structural view automatically. The envelope is
 * the seed of the future BOOK document: later phases add their own sections ({@code outline},
 * {@code content} = the manuscript, {@code style}, {@code images}, {@code sources}) when their
 * phase needs them — never earlier. Two guardrails for those sections: structural ORDER comes
 * from array position only (JSON property order is cosmetic, never semantic), and every section
 * gets its OWN view + tool contract on the shared JsonTree (the concept's name-chain addressing
 * would be wrong for a manuscript full of {@code paragraph} blocks).
 */
public final class FileConceptStore {

    /** A fresh concept: empty envelope, working revision 0, nothing approved. */
    public static final String EMPTY_DOCUMENT = "{\"title\":\"\",\"subtitle\":\"\",\"concept\":[]}";

    /** Outcome of an approval attempt. */
    public static final class Approval {
        private final boolean approved;
        private final int revisionNumber;

        private Approval(boolean approved, int revisionNumber) {
            this.approved = approved;
            this.revisionNumber = revisionNumber;
        }

        /** {@code false} means ALREADY_CURRENT: the working state equals the last approval. */
        public boolean isNewRevision() {
            return approved;
        }

        public int getRevisionNumber() {
            return revisionNumber;
        }
    }

    private final File dir;

    public FileConceptStore(File conceptDir) {
        this.dir = conceptDir;
    }

    /** The current working revision; 0 while nothing has ever been committed. */
    public synchronized long workingRevision() {
        return parseLong(workingProps().getProperty("workingRevision"), 0L);
    }

    /** Working copy if present, else the latest approved document, else the empty envelope. */
    public synchronized String effectiveContent() {
        try {
            File working = workingJson();
            if (working.isFile()) {
                return StoreIo.readUtf8(working);
            }
            int latest = latestApprovedNumber();
            if (latest > 0) {
                return StoreIo.readUtf8(revisionJson(latest));
            }
        } catch (IOException unreadable) {
            // fall through to the empty envelope — never fabricate partial content
        }
        return EMPTY_DOCUMENT;
    }

    /**
     * Commit a new working document. Content-hash dedupe: an identical document does NOT bump
     * the revision (a no-op edit must not invalidate everyone else's branch handles).
     *
     * @return the working revision after the commit (unchanged when deduped)
     */
    public synchronized long commitWorking(String documentJson, long nowMillis) {
        String hash = StoreIo.sha256(documentJson);
        Properties props = workingProps();
        long revision = parseLong(props.getProperty("workingRevision"), 0L);
        if (hash.equals(props.getProperty("contentHash")) && workingJson().isFile()) {
            return revision;
        }
        long next = revision + 1;
        try {
            StoreIo.atomicWrite(workingJson(), documentJson);
            StoreIo.atomicWrite(workingPropsFile(),
                    "workingRevision=" + next
                            + "\ncontentHash=" + hash
                            + "\nupdatedAt=" + nowMillis + "\n");
        } catch (IOException ex) {
            throw new IllegalStateException("Could not persist concept: " + ex.getMessage(), ex);
        }
        return next;
    }

    /** Freeze the current effective document as the next immutable approved revision. */
    public synchronized Approval approveCurrent(long nowMillis) {
        String content = effectiveContent();
        String hash = StoreIo.sha256(content);
        int latest = latestApprovedNumber();
        if (latest > 0 && hash.equals(revisionProps(latest).getProperty("contentHash"))) {
            return new Approval(false, latest);
        }
        int next = latest + 1;
        try {
            StoreIo.atomicWrite(revisionJson(next), content);
            StoreIo.atomicWrite(revisionPropsFile(next),
                    "revisionNumber=" + next
                            + "\ncontentHash=" + hash
                            + "\napprovedAt=" + nowMillis + "\n");
        } catch (IOException ex) {
            throw new IllegalStateException("Could not approve concept: " + ex.getMessage(), ex);
        }
        return new Approval(true, next);
    }

    /** The latest approved document, or {@code null} when nothing was approved yet. */
    public synchronized String latestApprovedContent() {
        int latest = latestApprovedNumber();
        if (latest <= 0) {
            return null;
        }
        try {
            return StoreIo.readUtf8(revisionJson(latest));
        } catch (IOException unreadable) {
            return null;
        }
    }

    public synchronized int latestApprovedNumber() {
        File revDir = new File(dir, "revisions");
        File[] files = revDir.listFiles();
        int latest = 0;
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (name.endsWith(".json")) {
                    try {
                        latest = Math.max(latest,
                                Integer.parseInt(name.substring(0, name.length() - ".json".length())));
                    } catch (NumberFormatException notANumber) {
                        // foreign file — ignore
                    }
                }
            }
        }
        return latest;
    }

    // ------------------------------------------------------------------ paths & props

    private File workingJson() {
        return new File(dir, "working.json");
    }

    private File workingPropsFile() {
        return new File(dir, "working.properties");
    }

    private File revisionJson(int number) {
        return new File(new File(dir, "revisions"), pad(number) + ".json");
    }

    private File revisionPropsFile(int number) {
        return new File(new File(dir, "revisions"), pad(number) + ".properties");
    }

    private Properties workingProps() {
        return readProps(workingPropsFile());
    }

    private Properties revisionProps(int number) {
        return readProps(revisionPropsFile(number));
    }

    private static String pad(int number) {
        return String.format("%04d", number);
    }

    private static Properties readProps(File file) {
        Properties props = new Properties();
        if (!file.isFile()) {
            return props;
        }
        try {
            InputStream in = new FileInputStream(file);
            try {
                props.load(in);
            } finally {
                in.close();
            }
        } catch (IOException corrupt) {
            // best-effort: defaults fill the rest
        }
        return props;
    }

    private static long parseLong(String text, long fallback) {
        try {
            return text == null ? fallback : Long.parseLong(text.trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }
}
