package com.aresstack.askai.java8.hf;

import com.aresstack.askai.java8.net.SystemTrustSslSocketFactory;

import java.util.List;

/**
 * The outcome of {@link HuggingFaceClient#testConnection()}: a real end-to-end HTTPS request against
 * HuggingFace over the exact same code path (proxy resolution, TLS handshake, certificate validation,
 * HTTP request) used by search and download.
 *
 * <p>Unlike the pure proxy/PAC "Resolve test URL" check, reaching an HTTP status here proves the TLS
 * handshake and certificate validation succeeded. A {@link #isCertificateFailure() certificate
 * failure} therefore points at the trust configuration, not the proxy.</p>
 */
public final class HuggingFaceConnectionTestResult {

    private final String url;
    private final String resolvedProxy;
    private final boolean handshakeSucceeded;
    private final int httpStatus;
    private final boolean certificateFailure;
    private final String errorSummary;
    private final SystemTrustSslSocketFactory.Result trust;

    private HuggingFaceConnectionTestResult(String url, String resolvedProxy, boolean handshakeSucceeded,
                                            int httpStatus, boolean certificateFailure, String errorSummary,
                                            SystemTrustSslSocketFactory.Result trust) {
        this.url = url;
        this.resolvedProxy = resolvedProxy;
        this.handshakeSucceeded = handshakeSucceeded;
        this.httpStatus = httpStatus;
        this.certificateFailure = certificateFailure;
        this.errorSummary = errorSummary;
        this.trust = trust;
    }

    static HuggingFaceConnectionTestResult success(String url, String resolvedProxy, int httpStatus,
                                                   SystemTrustSslSocketFactory.Result trust) {
        return new HuggingFaceConnectionTestResult(url, resolvedProxy, true, httpStatus, false, null, trust);
    }

    static HuggingFaceConnectionTestResult failure(String url, String resolvedProxy, boolean certificateFailure,
                                                   String errorSummary, SystemTrustSslSocketFactory.Result trust) {
        return new HuggingFaceConnectionTestResult(url, resolvedProxy, false, -1, certificateFailure, errorSummary, trust);
    }

    /** @return {@code true} when the TLS handshake completed and an HTTP status was received. */
    public boolean isHandshakeSucceeded() {
        return handshakeSucceeded;
    }

    /** @return {@code true} when the request completed with a 2xx HTTP status. */
    public boolean isSuccess() {
        return handshakeSucceeded && httpStatus >= 200 && httpStatus < 300;
    }

    /** @return {@code true} when the failure was a certificate/PKIX validation problem. */
    public boolean isCertificateFailure() {
        return certificateFailure;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getErrorSummary() {
        return errorSummary;
    }

    /**
     * @return a human-readable multi-line report suitable for the Network pane log, keeping proxy and
     *         certificate-trust diagnostics clearly separated.
     */
    public String describe() {
        StringBuilder builder = new StringBuilder();
        builder.append("HuggingFace HTTPS test").append('\n');
        builder.append("URL: ").append(url).append('\n');
        builder.append("Proxy: ").append(resolvedProxy).append('\n');
        if (isSuccess()) {
            builder.append("Result: SUCCESS (HTTP ").append(httpStatus).append(")").append('\n');
        } else if (handshakeSucceeded) {
            builder.append("Result: TLS/handshake OK, but HTTP ").append(httpStatus)
                    .append(" (proxy and certificate trust are fine)").append('\n');
        } else if (certificateFailure) {
            builder.append("Result: CERTIFICATE FAILURE (PKIX)").append('\n');
            builder.append("  The presented certificate chain is not trusted by the selected trust sources.").append('\n');
            builder.append("  This is a trust problem, not a proxy problem: ").append(errorSummary).append('\n');
        } else {
            builder.append("Result: FAILED: ").append(errorSummary).append('\n');
        }
        builder.append('\n');
        builder.append("Certificate trust:").append('\n');
        builder.append("JVM default=").append(trust.isJvmDefaultTrusted()).append('\n');
        builder.append("Windows-ROOT=").append(trust.isWindowsRootTrusted()).append('\n');
        builder.append("Windows CA stores=").append(trust.isWindowsCaStoresTrusted()).append('\n');
        builder.append("Windows exported certificates=").append(trust.getWindowsExportedCertificateCount());
        List<String> diagnostics = trust.getDiagnostics();
        for (int i = 0; i < diagnostics.size(); i++) {
            builder.append('\n').append("  - ").append(diagnostics.get(i));
        }
        return builder.toString();
    }
}
