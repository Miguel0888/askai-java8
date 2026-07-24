package com.aresstack.askai.java8.hf.catalog;

import com.aresstack.askai.java8.hf.HuggingFaceClient;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads the filter catalogs in the required source order: live HuggingFace data first, then the last
 * successfully cached live data, then the bundled offline resources. A successful live load is cached
 * to disk so the next offline start still has the full data.
 *
 * <p>The single source {@code /api/models-tags-by-type} feeds every group, so the origin is uniform
 * across groups; the returned {@link CatalogBundle} carries that origin plus the per-group counts and
 * a message describing any downgrade.</p>
 */
public final class CatalogRepository {

    private static final String MODELS_TAGS_URL = "https://huggingface.co/api/models-tags-by-type";

    private final File cacheFile;

    public CatalogRepository() {
        this(defaultCacheFile());
    }

    public CatalogRepository(File cacheFile) {
        this.cacheFile = cacheFile;
    }

    /**
     * @param client    the configured HuggingFace client (proxy/trust/token) for the live fetch
     * @param forceLive currently informational — the order is always Live → Cache → Fallback; kept so
     *                  a caller can express "the user asked to refresh" for logging
     * @return the best available catalogs with their origin
     */
    public CatalogBundle load(HuggingFaceClient client, boolean forceLive) {
        String liveError = "";
        if (client != null) {
            try {
                String json = client.fetchText(MODELS_TAGS_URL);
                FilterCatalogs catalogs = LiveCatalogParser.parse(json);
                if (!catalogs.getTasks().isEmpty() && !catalogs.getLibraries().isEmpty()) {
                    writeCache(json);
                    return new CatalogBundle(catalogs, CatalogOrigin.LIVE, "");
                }
                liveError = "Live-Daten leer";
            } catch (Exception ex) {
                liveError = describe(ex);
            }
        } else {
            liveError = "kein HTTP-Client";
        }

        // Live failed -> last successful cache.
        String cached = readCache();
        if (cached != null) {
            try {
                FilterCatalogs catalogs = LiveCatalogParser.parse(cached);
                if (!catalogs.getTasks().isEmpty()) {
                    return new CatalogBundle(catalogs, CatalogOrigin.CACHE,
                            "Live nicht erreichbar (" + liveError + ") — zwischengespeicherte Daten.");
                }
            } catch (Exception ignored) {
                // fall through to bundled fallback
            }
        }

        // No usable cache -> bundled offline snapshot.
        return new CatalogBundle(CatalogLoader.load(), CatalogOrigin.FALLBACK,
                "Live und Cache nicht verfügbar (" + liveError + ") — gebündelte Daten.");
    }

    /** @return the newest offline-available bundle without any network attempt (cache, else bundled). */
    public CatalogBundle loadOffline() {
        String cached = readCache();
        if (cached != null) {
            try {
                FilterCatalogs catalogs = LiveCatalogParser.parse(cached);
                if (!catalogs.getTasks().isEmpty()) {
                    return new CatalogBundle(catalogs, CatalogOrigin.CACHE, "zwischengespeicherte Daten");
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        return new CatalogBundle(CatalogLoader.load(), CatalogOrigin.FALLBACK, "gebündelte Daten");
    }

    private void writeCache(String json) {
        File directory = cacheFile.getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
            return;
        }
        OutputStream out = null;
        try {
            out = new FileOutputStream(cacheFile);
            out.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // A failed cache write is non-fatal; the live data is still returned.
        } finally {
            closeQuietly(out);
        }
    }

    private String readCache() {
        if (!cacheFile.isFile()) {
            return null;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(cacheFile);
            byte[] buffer = new byte[8192];
            StringBuilder builder = new StringBuilder();
            int read;
            while ((read = in.read(buffer)) >= 0) {
                builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
            return builder.toString();
        } catch (IOException ex) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static File defaultCacheFile() {
        String appData = System.getenv("APPDATA");
        File base;
        if (appData != null && appData.trim().length() > 0) {
            base = new File(appData, ".askai-java8");
        } else {
            base = new File(System.getProperty("user.home"), ".askai-java8");
        }
        return new File(new File(base, "catalog-cache"), "models-tags-by-type.json");
    }

    private static String describe(Exception ex) {
        String message = ex.getMessage();
        return message != null && message.trim().length() > 0 ? message : ex.getClass().getSimpleName();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }
}
