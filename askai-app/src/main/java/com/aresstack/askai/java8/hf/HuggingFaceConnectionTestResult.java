package com.aresstack.askai.java8.hf;

import com.aresstack.askai.java8.net.SystemTrustSslSocketFactory;

import java.util.Collections;
import java.util.List;

/**
 * The outcome of {@link HuggingFaceClient#testConnection()}: several real HTTPS requests against
 * HuggingFace over the exact same code path (proxy resolution, TLS handshake, certificate validation,
 * HTTP request) used by search and download, probed with more than one User-Agent.
 *
 * <p>Reaching an HTTP status proves the TLS handshake and certificate validation succeeded, so a
 * non-2xx status is an HTTP/policy matter, not a certificate matter. For non-2xx responses each probe
 * carries the relevant response headers, extracted HTML (title/visible text/highlighted terms) and a
 * heuristic assessment of whether the block came from a corporate proxy or from HuggingFace itself.</p>
 */
public final class HuggingFaceConnectionTestResult {

    /** The outcome for a single probed (URL, User-Agent) pair. */
    public static final class Endpoint {

        private final String url;
        private final String userAgentLabel;
        private final boolean handshakeSucceeded;
        private final int httpStatus;
        private final boolean certificateFailure;
        private final String errorSummary;
        private final String assessment;
        private final List<String> detailLines;

        private Endpoint(String url, String userAgentLabel, boolean handshakeSucceeded, int httpStatus,
                         boolean certificateFailure, String errorSummary, String assessment, List<String> detailLines) {
            this.url = url;
            this.userAgentLabel = userAgentLabel;
            this.handshakeSucceeded = handshakeSucceeded;
            this.httpStatus = httpStatus;
            this.certificateFailure = certificateFailure;
            this.errorSummary = errorSummary;
            this.assessment = assessment == null ? "" : assessment;
            this.detailLines = detailLines == null ? Collections.<String>emptyList() : detailLines;
        }

        static Endpoint http(String url, String userAgentLabel, int httpStatus, String assessment,
                             List<String> detailLines) {
            return new Endpoint(url, userAgentLabel, true, httpStatus, false, null, assessment, detailLines);
        }

        static Endpoint error(String url, String userAgentLabel, boolean certificateFailure, String errorSummary) {
            return new Endpoint(url, userAgentLabel, false, -1, certificateFailure, errorSummary, null, null);
        }

        public boolean isSuccess() {
            return handshakeSucceeded && httpStatus >= 200 && httpStatus < 300;
        }

        public int getHttpStatus() {
            return httpStatus;
        }

        public boolean isCertificateFailure() {
            return certificateFailure;
        }

        private void appendTo(StringBuilder builder) {
            builder.append("Endpoint: ").append(url).append("  [UA: ").append(userAgentLabel).append("]").append('\n');
            if (!handshakeSucceeded) {
                if (certificateFailure) {
                    builder.append("  TLS: FAILED (certificate/PKIX) — ").append(errorSummary).append('\n');
                } else {
                    builder.append("  TLS/connection: FAILED — ").append(errorSummary).append('\n');
                }
                return;
            }
            // TLS + proxy both succeeded whenever an HTTP status came back.
            builder.append("  TLS: OK").append('\n');
            builder.append("  Proxy: OK (reached; HTTP response received)").append('\n');
            if (isSuccess()) {
                builder.append("  HTTP: ").append(httpStatus).append(" OK").append('\n');
            } else {
                builder.append("  HTTP: ").append(httpStatus).append(" — blocked / auth required").append('\n');
            }
            if (assessment.length() > 0) {
                builder.append("  Assessment: ").append(assessment).append('\n');
            }
            for (int i = 0; i < detailLines.size(); i++) {
                builder.append("  ").append(detailLines.get(i)).append('\n');
            }
        }
    }

    private final String resolvedProxy;
    private final SystemTrustSslSocketFactory.Result trust;
    private final List<String> notes;
    private final List<Endpoint> endpoints;

    HuggingFaceConnectionTestResult(String resolvedProxy, SystemTrustSslSocketFactory.Result trust,
                                    List<String> notes, List<Endpoint> endpoints) {
        this.resolvedProxy = resolvedProxy;
        this.trust = trust;
        this.notes = notes == null ? Collections.<String>emptyList() : notes;
        this.endpoints = endpoints;
    }

    /** @return {@code true} when any probe returned a 2xx status. */
    public boolean isSuccess() {
        for (int i = 0; i < endpoints.size(); i++) {
            if (endpoints.get(i).isSuccess()) {
                return true;
            }
        }
        return false;
    }

    /** @return {@code true} when any probe failed the TLS handshake with a certificate/PKIX error. */
    public boolean isCertificateFailure() {
        for (int i = 0; i < endpoints.size(); i++) {
            if (endpoints.get(i).isCertificateFailure()) {
                return true;
            }
        }
        return false;
    }

    private Endpoint apiEndpoint() {
        return endpoints.isEmpty() ? null : endpoints.get(endpoints.size() - 1);
    }

    /** @return a one-line summary suitable for a dialog. */
    public String shortSummary() {
        if (isSuccess()) {
            return "HuggingFace HTTPS OK — TLS, certificate trust and at least one request succeeded.";
        }
        if (isCertificateFailure()) {
            return "Certificate/PKIX failure — see the Network log for trust diagnostics.";
        }
        Endpoint api = apiEndpoint();
        int status = api == null ? -1 : api.getHttpStatus();
        if (status > 0) {
            return "TLS and proxy are OK, but HTTP " + status + " was returned for every User-Agent. "
                    + "This is an HTTP policy/auth matter (proxy or origin), not a certificate issue. "
                    + "See the Network log for headers, extracted page text and the assessment.";
        }
        return "HuggingFace HTTPS test failed before receiving an HTTP status. See the Network log for details.";
    }

    /**
     * @return a multi-line report for the Network pane log: proxy + certificate-trust diagnostics and
     *         request notes once, then per (URL, User-Agent) status, headers and extracted page text.
     */
    public String describe() {
        StringBuilder builder = new StringBuilder();
        builder.append("HuggingFace HTTPS test").append('\n');
        builder.append("Proxy: ").append(resolvedProxy).append('\n');
        for (int i = 0; i < notes.size(); i++) {
            builder.append(notes.get(i)).append('\n');
        }
        builder.append('\n');
        builder.append("Certificate trust:").append('\n');
        builder.append("JVM default=").append(trust.isJvmDefaultTrusted()).append('\n');
        builder.append("Windows-ROOT=").append(trust.isWindowsRootTrusted()).append('\n');
        builder.append("Windows CA stores=").append(trust.isWindowsCaStoresTrusted()).append('\n');
        builder.append("Windows exported certificates=").append(trust.getWindowsExportedCertificateCount()).append('\n');
        List<String> diagnostics = trust.getDiagnostics();
        for (int i = 0; i < diagnostics.size(); i++) {
            builder.append("  - ").append(diagnostics.get(i)).append('\n');
        }
        for (int i = 0; i < endpoints.size(); i++) {
            builder.append('\n');
            endpoints.get(i).appendTo(builder);
        }
        builder.append('\n');
        builder.append("Note: the assessment is a heuristic. A non-2xx status here means TLS/certificate "
                + "validation already succeeded; the block is an HTTP policy/auth/allowlisting matter. If the "
                + "browser User-Agent also gets 403, it is almost certainly proxy auth/policy, not User-Agent "
                + "cosmetics.");
        return builder.toString();
    }
}
