package com.aresstack.askai.java8.net;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Provides an {@link SSLSocketFactory} that trusts certificates from the JVM default trust store
 * (the bundled {@code cacerts} file) <em>and</em> the Windows system root store ({@code Windows-ROOT}).
 *
 * <p>Corporate proxies frequently terminate TLS and re-sign responses with a private CA. That CA is
 * installed in the Windows certificate store (so browsers and {@code curl --ssl-no-revoke} work) but
 * is unknown to the JVM's bundled {@code cacerts}. Without merging the Windows store, every HTTPS
 * request to huggingface.co fails during the TLS handshake with
 * {@code PKIX path building failed ... unable to find valid certification path to requested target},
 * even though proxy discovery/resolution succeeds.</p>
 *
 * <p>The factory only ever <em>adds</em> trust anchors that the operating system already trusts, so
 * it does not weaken certificate validation. On non-Windows platforms (or when SunMSCAPI is
 * unavailable) the Windows store is silently skipped and only the JVM default trust store is used.</p>
 */
public final class SystemTrustSslSocketFactory {

    private static volatile SSLSocketFactory cached;

    private SystemTrustSslSocketFactory() {
    }

    /**
     * @return a cached socket factory trusting both the JVM default and Windows root certificates,
     *         falling back to the JVM default socket factory if a combined context cannot be built.
     */
    public static SSLSocketFactory get() {
        SSLSocketFactory factory = cached;
        if (factory == null) {
            synchronized (SystemTrustSslSocketFactory.class) {
                factory = cached;
                if (factory == null) {
                    factory = build();
                    cached = factory;
                }
            }
        }
        return factory;
    }

    private static SSLSocketFactory build() {
        List<X509TrustManager> delegates = new ArrayList<X509TrustManager>();
        addTrustManager(delegates, null);            // JVM default (cacerts)
        addWindowsRootTrustManager(delegates);       // Windows-ROOT (no-op off Windows)

        if (delegates.isEmpty()) {
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }

        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new CompositeX509TrustManager(delegates)}, null);
            return context.getSocketFactory();
        } catch (GeneralSecurityException ex) {
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
    }

    private static void addWindowsRootTrustManager(List<X509TrustManager> delegates) {
        try {
            KeyStore keyStore = KeyStore.getInstance("Windows-ROOT");
            keyStore.load(null, null);
            addTrustManager(delegates, keyStore);
        } catch (Exception ex) {
            // Not running on Windows or SunMSCAPI unavailable: the JVM default trust store is enough.
        }
    }

    private static void addTrustManager(List<X509TrustManager> delegates, KeyStore keyStore) {
        try {
            TrustManagerFactory factory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(keyStore);
            for (TrustManager trustManager : factory.getTrustManagers()) {
                if (trustManager instanceof X509TrustManager) {
                    delegates.add((X509TrustManager) trustManager);
                }
            }
        } catch (GeneralSecurityException ex) {
            // Ignore: this trust source is simply not added to the composite.
        }
    }

    /**
     * Accepts a certificate chain when <em>any</em> of the delegate trust managers accepts it.
     */
    private static final class CompositeX509TrustManager implements X509TrustManager {

        private final List<X509TrustManager> delegates;

        CompositeX509TrustManager(List<X509TrustManager> delegates) {
            this.delegates = delegates;
        }

        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            CertificateException last = null;
            for (X509TrustManager delegate : delegates) {
                try {
                    delegate.checkClientTrusted(chain, authType);
                    return;
                } catch (CertificateException ex) {
                    last = ex;
                }
            }
            throw last != null ? last
                    : new CertificateException("No trust manager accepted the client certificate chain.");
        }

        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            CertificateException last = null;
            for (X509TrustManager delegate : delegates) {
                try {
                    delegate.checkServerTrusted(chain, authType);
                    return;
                } catch (CertificateException ex) {
                    last = ex;
                }
            }
            throw last != null ? last
                    : new CertificateException("No trust manager accepted the server certificate chain.");
        }

        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> issuers = new ArrayList<X509Certificate>();
            for (X509TrustManager delegate : delegates) {
                X509Certificate[] certificates = delegate.getAcceptedIssuers();
                if (certificates != null) {
                    for (X509Certificate certificate : certificates) {
                        issuers.add(certificate);
                    }
                }
            }
            return issuers.toArray(new X509Certificate[issuers.size()]);
        }
    }
}
