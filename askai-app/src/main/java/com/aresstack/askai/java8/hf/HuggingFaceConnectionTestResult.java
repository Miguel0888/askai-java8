package com.aresstack.askai.java8.hf;

import com.aresstack.askai.java8.net.SystemTrustSslSocketFactory;

import java.util.Collections;
import java.util.List;

/**
 * The outcome of {@link HuggingFaceClient#testConnection()}: one or more real HTTPS requests against
 * HuggingFace over the exact same code path (proxy resolution, TLS handshake, certificate validation,
 * HTTP request) used by search and download.
 *
 * <p>Reaching an HTTP status proves the TLS handshake and certificate validation succeeded, so a
 * non-2xx status is an HTTP/policy matter, not a certificate matter. For non-2xx responses the result
 * therefore also carries the relevant response headers and a body excerpt plus a heuristic assessment
 * of whether the block came from a corporate proxy or from HuggingFace/Cloudflare itself.</p>
 */
public final class HuggingFaceConnectionTestResult {

    /** The outcome for a single probed URL. */
    public static final class Endpoint {

        private final String url;
        private final boolean handshakeSucceeded;
        private final int httpStatus;
        private final boolean certificateFailure;
        private final String errorSummary;
        private final List<String> headers;
        private final String bodyExcerpt;
        private final String assessment;

        private Endpoint(String url, boolean handshakeSucceeded, int httpStatus, boolean certificateFailure,
                         String errorSummary, List<String> headers, String bodyExcerpt, String assessment) {
            this.url = url;
            this.handshakeSucceeded = handshakeSucceeded;
            this.httpStatus = httpStatus;
            this.certificateFailure = certificateFailure;
            this.errorSummary = errorSummary;
            this.headers = headers == null ? Collections.<String>emptyList() : headers;
            this.bodyExcerpt = bodyExcerpt == null ? "" : bodyExcerpt;
            this.assessment = assessment == null ? "" : assessment;
        }

        static Endpoint http(String url, int httpStatus, List<String> headers, String bodyExcerpt, String assessment) {
            return new Endpoint(url, true, httpStatus, false, null, headers, bodyExcerpt, assessment);
        }

        static Endpoint error(String url, boolean certificateFailure, String errorSummary) {
            return new Endpoint(url, false, -1, certificateFailure, errorSummary, null, null, null);
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
            builder.append("Endpoint: ").append(url).append('\n');
            if (!handshakeSucceeded) {
                if (certificateFailure) {
                    builder.append("  Result: CERTIFICATE FAILURE (PKIX) — ").append(errorSummary).append('\n');
                } else {
                    builder.append("  Result: FAILED (no HTTP response) — ").append(errorSummary).append('\n');
                }
                return;
            }
            if (isSuccess()) {
                builder.append("  Result: HTTP ").append(httpStatus).append(" (OK)").append('\n');
            } else {
                builder.append("  Result: TLS/handshake OK, but HTTP ").append(httpStatus)
                        .append(" (proxy and certificate trust are fine)").append('\n');
            }
            if (assessment.length() > 0) {
                builder.append("  Assessment: ").append(assessment).append('\n');
            }
            if (!headers.isEmpty()) {
                builder.append("  Response headers:").append('\n');
                for (int i = 0; i < headers.size(); i++) {
                    builder.append("    ").append(headers.get(i)).append('\n');
                }
            }
            if (bodyExcerpt.length() > 0) {
                builder.append("  Body (excerpt):").append('\n');
                String[] lines = bodyExcerpt.split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    builder.append("    ").append(lines[i]).append('\n');
                }
            }
        }
    }

    private final String resolvedProxy;
    private final SystemTrustSslSocketFactory.Result trust;
    private final List<Endpoint> endpoints;

    HuggingFaceConnectionTestResult(String resolvedProxy, SystemTrustSslSocketFactory.Result trust,
                                    List<Endpoint> endpoints) {
        this.resolvedProxy = resolvedProxy;
        this.trust = trust;
        this.endpoints = endpoints;
    }

    /**
     * @return {@code true} when the API endpoint (the last probe) returned a 2xx status.
     */
    public boolean isSuccess() {
        Endpoint api = apiEndpoint();
        return api != null && api.isSuccess();
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

    /** @return a one-line summary suitable for a dialog title/body. */
    public String shortSummary() {
        if (isSuccess()) {
            return "HuggingFace HTTPS OK — TLS, certificate trust and the API request all succeeded.";
        }
        if (isCertificateFailure()) {
            return "Certificate/PKIX failure — see the Network log for trust diagnostics.";
        }
        Endpoint api = apiEndpoint();
        int status = api == null ? -1 : api.getHttpStatus();
        if (status > 0) {
            return "TLS OK, but the API returned HTTP " + status
                    + ". This is an HTTP/policy issue, not a certificate issue. See the Network log for headers and body.";
        }
        return "HuggingFace HTTPS test failed before receiving an HTTP status. See the Network log for details.";
    }

    /**
     * @return a human-readable multi-line report for the Network pane log: the shared proxy and
     *         certificate-trust diagnostics once, then per-endpoint status, headers and body.
     */
    public String describe() {
        StringBuilder builder = new StringBuilder();
        builder.append("HuggingFace HTTPS test").append('\n');
        builder.append("Proxy: ").append(resolvedProxy).append('\n');
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
                + "validation already succeeded; the block is an HTTP policy/auth/allowlisting matter.");
        return builder.toString();
    }
}
