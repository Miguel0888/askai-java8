package com.aresstack.askai.research.store;

import com.aresstack.askai.research.review.PostSearchReviewLedger;
import com.aresstack.askai.research.review.SourceCorpusRevision;

import java.io.File;
import java.io.IOException;

/**
 * Persists the project's {@link PostSearchReviewLedger} as {@code state/post-search-review.properties},
 * written atomically. A missing or corrupt file yields {@link PostSearchReviewLedger#INITIAL} — a project
 * that cannot say what it reviewed has reviewed nothing, which offers a review too often rather than
 * silently swallowing one.
 */
public final class FilePostSearchReviewStore {

    private static final String REVIEWED_COUNT = "reviewedThrough.count";
    private static final String REVIEWED_LATEST = "reviewedThrough.latestCapturedAt";
    private static final String FAILED_PRESENT = "failedOn.present";
    private static final String FAILED_COUNT = "failedOn.count";
    private static final String FAILED_LATEST = "failedOn.latestCapturedAt";

    private final File file;

    public FilePostSearchReviewStore(File stateDir) {
        this.file = new File(stateDir, "post-search-review.properties");
    }

    public PostSearchReviewLedger load() {
        if (!file.isFile()) {
            return PostSearchReviewLedger.INITIAL;
        }
        try {
            java.util.Properties props = new java.util.Properties();
            java.io.InputStream in = new java.io.FileInputStream(file);
            try {
                props.load(new java.io.InputStreamReader(in, "UTF-8"));
            } finally {
                in.close();
            }
            SourceCorpusRevision reviewed = new SourceCorpusRevision(
                    intOf(props, REVIEWED_COUNT), longOf(props, REVIEWED_LATEST));
            SourceCorpusRevision failed = Boolean.parseBoolean(props.getProperty(FAILED_PRESENT, "false"))
                    ? new SourceCorpusRevision(intOf(props, FAILED_COUNT), longOf(props, FAILED_LATEST))
                    : null;
            return new PostSearchReviewLedger(reviewed, failed);
        } catch (Exception corrupt) {
            return PostSearchReviewLedger.INITIAL;
        }
    }

    public void save(PostSearchReviewLedger ledger) throws IOException {
        SourceCorpusRevision reviewed = ledger.getReviewedThrough();
        SourceCorpusRevision failed = ledger.getFailedOn();
        StringBuilder text = new StringBuilder();
        text.append(REVIEWED_COUNT).append('=').append(reviewed.getReviewableCount()).append('\n');
        text.append(REVIEWED_LATEST).append('=').append(reviewed.getLatestCapturedAt()).append('\n');
        text.append(FAILED_PRESENT).append('=').append(failed != null).append('\n');
        if (failed != null) {
            text.append(FAILED_COUNT).append('=').append(failed.getReviewableCount()).append('\n');
            text.append(FAILED_LATEST).append('=').append(failed.getLatestCapturedAt()).append('\n');
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        StoreIo.atomicWrite(file, text.toString());
    }

    private static int intOf(java.util.Properties props, String key) {
        return (int) longOf(props, key);
    }

    private static long longOf(java.util.Properties props, String key) {
        try {
            return Long.parseLong(props.getProperty(key, "0").trim());
        } catch (NumberFormatException notANumber) {
            return 0L;
        }
    }
}
