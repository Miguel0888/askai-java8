package com.aresstack.askai.java8.localmodels;

import com.aresstack.askai.java8.hf.DownloadProgressListener;
import com.aresstack.askai.java8.hf.HuggingFaceClient;
import com.aresstack.askai.java8.hf.HuggingFaceFile;
import com.aresstack.windirectml.catalog.InstalledModelManifest;
import com.aresstack.windirectml.catalog.LocalRuntimeModelDescriptor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The staged LOCAL installation of a compatible Hugging Face model:
 * resolve revision → download the raw files (checksums verified by the HF client) → re-check
 * compatibility against the LOCAL files → compile {@code reranker.wdmlpack} + runtime smoke-load
 * in the Java-21 sidecar → write the provenance manifest → atomically rename staging into the
 * final model directory. Nothing half-installed ever becomes visible: the sidecar only publishes
 * directories whose manifest says RUNNABLE, and that manifest is written LAST.
 */
public final class LocalModelInstaller {

    /** Step-by-step progress for the UI; download progress reports bytes. */
    public interface Listener {
        void onStep(String step);

        void onDownloadProgress(String fileName, long completed, long total);
    }

    private final HuggingFaceClient client;
    private final LocalModelRuntimeManager manager;
    private final LocalModelCompatibilityAnalyzer analyzer = new LocalModelCompatibilityAnalyzer();

    public LocalModelInstaller(HuggingFaceClient client, LocalModelRuntimeManager manager) {
        this.client = client;
        this.manager = manager;
    }

    /** @return the virtual model name after a fully verified installation. */
    public String install(String repositoryId, final Listener listener) throws IOException {
        // Family is decided by the neutral catalog, never by a name heuristic. Generation families are
        // catalogued but their local installer is not wired yet; encoders land with the sidecar's
        // encoder-package path in the next commit; the reranker is installable now.
        LocalModelInstallResolution resolution = LocalModelInstallResolution.resolve(repositoryId);
        if (!resolution.isInstallable()) {
            throw new IOException(resolution.getMessage() != null ? resolution.getMessage()
                    : "'" + repositoryId + "' cannot be installed into the local runtime.");
        }
        if (resolution.getKind() == LocalModelInstallResolution.Kind.ENCODER) {
            throw new IOException("Local installation of embedding encoders is not enabled yet in this "
                    + "build (the sidecar encoder-package path lands in the next commit): " + repositoryId);
        }
        LocalRuntimeModelDescriptor descriptor = resolution.getDescriptor();

        listener.onStep("Resolving repository revision…");
        String revision = client.resolveRevisionSha(repositoryId, "main");
        List<HuggingFaceFile> remoteFiles = client.listAllFilesDetailed(repositoryId);

        listener.onStep("Checking local runtime compatibility…");
        LocalModelCompatibilityResult remoteCheck = analyzer.analyze(repositoryId,
                paths(remoteFiles),
                fetchText(repositoryId, revision, "config.json"),
                fetchText(repositoryId, revision, "tokenizer.json"));
        if (!remoteCheck.isSupported()) {
            throw new IOException("Not supported by the local runtime: " + remoteCheck.getReason());
        }

        File localRoot = manager.getModelRoot();
        if (!localRoot.isDirectory() && !localRoot.mkdirs()) {
            throw new IOException("Cannot create local model root: " + localRoot);
        }
        File staging = new File(localRoot,
                ".staging-" + remoteCheck.getRuntimeDirectoryName() + "-" + System.nanoTime());
        // The runtime validates the FINAL path component strictly, so the assembly directory
        // already carries the canonical name and is renamed as a whole at the end.
        File assembly = new File(staging, remoteCheck.getRuntimeDirectoryName());
        if (!assembly.mkdirs()) {
            throw new IOException("Cannot create staging directory: " + assembly);
        }
        try {
            for (String required : LocalModelCompatibilityAnalyzer.REQUIRED_FILES) {
                final HuggingFaceFile file = findFile(remoteFiles, required);
                listener.onStep("Downloading " + required + "…");
                final String fileName = required;
                File downloaded = client.download(file, staging, revision,
                        new DownloadProgressListener() {
                            public void onProgress(long completed, long total) {
                                listener.onDownloadProgress(fileName, completed, total);
                            }
                        });
                Files.move(downloaded.toPath(), new File(assembly, required).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }

            listener.onStep("Re-checking compatibility against the downloaded files…");
            LocalModelCompatibilityResult localCheck = analyzer.analyze(repositoryId,
                    Arrays.asList(assembly.list() == null ? new String[0] : assembly.list()),
                    readText(new File(assembly, "config.json")),
                    readText(new File(assembly, "tokenizer.json")));
            if (!localCheck.isSupported()) {
                throw new IOException("Downloaded files failed the compatibility re-check: "
                        + localCheck.getReason());
            }

            listener.onStep("Compiling the runtime package (reranker.wdmlpack)…");
            String baseUrl = manager.ensureStarted();
            Map<String, Object> installRequest = new LinkedHashMap<String, Object>();
            installRequest.put("modelDir", assembly.getAbsolutePath());
            installRequest.put("runtimeModelId", localCheck.getRuntimeModelId());
            Map<String, Object> installed =
                    LocalRuntimeHttp.postJson(baseUrl, "/internal/install", installRequest);
            if (!Boolean.TRUE.equals(installed.get("ok"))) {
                throw new IOException("Runtime package build failed: " + installed.get("reason"));
            }

            listener.onStep("Writing the installation manifest…");
            String virtualName = descriptor.virtualModelName();
            // Manifest v2 (shared, catalog-validated on read): the catalog facts plus the resolved revision
            // and install time. Written LAST, after the verified compile + smoke-load.
            Files.write(new File(assembly, "askai-local-model.json").toPath(),
                    LocalModelManifestCodec.toJson(InstalledModelManifest.forInstall(descriptor, revision,
                            System.currentTimeMillis())).getBytes(Charset.forName("UTF-8")));

            listener.onStep("Activating the model…");
            File finalDirectory = new File(localRoot, localCheck.getRuntimeDirectoryName());
            if (finalDirectory.exists()) {
                // Reinstallation: ask the sidecar to unload+delete the previous installation first.
                try {
                    LocalRuntimeHttp.postJson(baseUrl, "/api/generate", unloadRequest(virtualName));
                } catch (IOException ignored) {
                    // the previous model may not be loaded — deletion below still applies
                }
                deleteRecursively(finalDirectory);
            }
            try {
                Files.move(assembly.toPath(), finalDirectory.toPath(),
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException notAtomic) {
                Files.move(assembly.toPath(), finalDirectory.toPath());
            }
            return virtualName;
        } finally {
            deleteRecursively(staging);
        }
    }

    private static Map<String, Object> unloadRequest(String virtualName) {
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("model", virtualName);
        request.put("keep_alive", 0);
        return request;
    }

    // ------------------------------------------------------------------ helpers

    private String fetchText(String repositoryId, String revision, String path) {
        try {
            return client.fetchFileText(repositoryId, revision, path);
        } catch (IOException unavailable) {
            return null;
        }
    }

    private static String readText(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), Charset.forName("UTF-8"));
    }

    private static HuggingFaceFile findFile(List<HuggingFaceFile> files, String name)
            throws IOException {
        for (HuggingFaceFile file : files) {
            if (file.getPath().equals(name)) {
                return file;
            }
        }
        throw new IOException("Repository file missing: " + name);
    }

    private static List<String> paths(List<HuggingFaceFile> files) {
        List<String> paths = new ArrayList<String>();
        for (HuggingFaceFile file : files) {
            paths.add(file.getPath());
        }
        return paths;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete()) {
            file.deleteOnExit();
        }
    }
}
