package com.aresstack.askai.research.capture;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-session, in-memory store of visited captures with a BOUNDED lifecycle: at most {@code maxCaptures}
 * are kept, oldest evicted first — captures are temporary working material, never silently persisted.
 * Recording computes the canonical URL (lowercased scheme/host, default ports and fragments dropped,
 * tracking parameters removed) and a SHA-256 content hash of the cleaned text.
 */
public final class CaptureStore {

    private final int maxCaptures;
    private final Map<String, VisitedCapture> byId = new LinkedHashMap<String, VisitedCapture>();
    private final AtomicLong ids = new AtomicLong();
    private final long fixedNow; // injectable time for deterministic tests; negative = system time

    public CaptureStore(int maxCaptures) {
        this(maxCaptures, -1L);
    }

    public CaptureStore(int maxCaptures, long fixedNow) {
        this.maxCaptures = Math.max(1, maxCaptures);
        this.fixedNow = fixedNow;
    }

    /** Record a visit (VISITED). Never creates a source and never touches the index. */
    public synchronized VisitedCapture record(String url, String title, String cleanedText) {
        String id = "cap-" + ids.incrementAndGet();
        VisitedCapture capture = new VisitedCapture(id, url, canonicalize(url), title, cleanedText,
                sha256(cleanedText == null ? "" : cleanedText),
                fixedNow >= 0 ? fixedNow : System.currentTimeMillis(), null, null, null);
        byId.put(id, capture);
        while (byId.size() > maxCaptures) {
            byId.remove(byId.keySet().iterator().next()); // evict oldest
        }
        return capture;
    }

    /** Attach candidate assessment metadata (CANDIDATE). Still not a source, still not indexed. */
    public synchronized VisitedCapture assess(String captureId, String relevance, String sourceType,
                                              String note) {
        VisitedCapture capture = byId.get(captureId);
        if (capture == null) {
            return null;
        }
        VisitedCapture assessed = capture.withAssessment(relevance, sourceType, note);
        byId.put(captureId, assessed);
        return assessed;
    }

    public synchronized VisitedCapture get(String captureId) {
        return byId.get(captureId);
    }

    public synchronized List<VisitedCapture> list() {
        return new ArrayList<VisitedCapture>(byId.values());
    }

    public synchronized int size() {
        return byId.size();
    }

    // ------------------------------------------------------------------ helpers

    /** Canonicalization for dedup: fragment + tracking params stripped, host/scheme lowercased. */
    public static String canonicalize(String url) {
        if (url == null) {
            return "";
        }
        String u = url.trim();
        int frag = u.indexOf('#');
        if (frag >= 0) {
            u = u.substring(0, frag);
        }
        int q = u.indexOf('?');
        if (q >= 0) {
            StringBuilder kept = new StringBuilder();
            String[] params = u.substring(q + 1).split("&");
            for (String param : params) {
                int eq = param.indexOf('=');
                String key = (eq >= 0 ? param.substring(0, eq) : param).toLowerCase(Locale.ROOT);
                boolean tracking = key.startsWith("utm_") || key.equals("ref")
                        || key.equals("fbclid") || key.equals("gclid");
                if (!tracking) {
                    if (kept.length() > 0) {
                        kept.append('&');
                    }
                    kept.append(param);
                }
            }
            u = u.substring(0, q) + (kept.length() == 0 ? "" : "?" + kept);
        }
        int schemeEnd = u.indexOf("://");
        if (schemeEnd > 0) {
            int hostEnd = u.indexOf('/', schemeEnd + 3);
            String prefix = hostEnd < 0 ? u : u.substring(0, hostEnd);
            String rest = hostEnd < 0 ? "" : u.substring(hostEnd);
            prefix = prefix.toLowerCase(Locale.ROOT);
            if (prefix.endsWith(":80") || prefix.endsWith(":443")) {
                prefix = prefix.substring(0, prefix.lastIndexOf(':'));
            }
            u = prefix + rest;
        }
        if (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(Charset.forName("UTF-8")));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception ex) {
            return "";
        }
    }
}
