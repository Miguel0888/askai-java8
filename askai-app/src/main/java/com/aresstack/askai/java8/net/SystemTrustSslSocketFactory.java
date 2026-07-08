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

public final class SystemTrustSslSocketFactory {

    private static volatile String cachedKey;
    private static volatile SSLSocketFactory cached;

    private SystemTrustSslSocketFactory() {
    }

    public static SSLSocketFactory get() {
        return get(CertificateTrustConfiguration.defaults());
    }

    public static SSLSocketFactory get(CertificateTrustConfiguration configuration) {
        CertificateTrustConfiguration effective = configuration == null
                ? CertificateTrustConfiguration.defaults()
                : configuration;
        String key = effective.key();
        SSLSocketFactory factory = cached;
        if (factory == null || !key.equals(cachedKey)) {
            synchronized (SystemTrustSslSocketFactory.class) {
                factory = cached;
                if (factory == null || !key.equals(cachedKey)) {
                    factory = build(effective);
                    cached = factory;
                    cachedKey = key;
                }
            }
        }
        return factory;
    }

    public static String describe(CertificateTrustConfiguration configuration) {
        CertificateTrustConfiguration effective = configuration == null
                ? CertificateTrustConfiguration.defaults()
                : configuration;
        StringBuilder builder = new StringBuilder();
        builder.append("Certificate trust: ").append(effective.describe());
        if (effective.isUseWindowsCaStores()) {
            builder.append("; Windows exported certificates: ")
                    .append(WindowsCertificateStores.loadRootAndIntermediateCertificates().size());
        }
        return builder.toString();
    }

    private static SSLSocketFactory build(CertificateTrustConfiguration configuration) {
        List<X509TrustManager> delegates = new ArrayList<X509TrustManager>();
        if (configuration.isUseJvmDefaultTrustStore()) {
            addTrustManager(delegates, null);
        }
        if (configuration.isUseWindowsRootStore()) {
            addWindowsRootTrustManager(delegates);
        }
        if (configuration.isUseWindowsCaStores()) {
            addWindowsCaTrustManager(delegates);
        }

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
            // Not running on Windows or SunMSCAPI unavailable.
        }
    }

    private static void addWindowsCaTrustManager(List<X509TrustManager> delegates) {
        List<X509Certificate> certificates = WindowsCertificateStores.loadRootAndIntermediateCertificates();
        if (certificates.isEmpty()) {
            return;
        }
        try {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            for (int i = 0; i < certificates.size(); i++) {
                keyStore.setCertificateEntry("askai-windows-ca-" + i, certificates.get(i));
            }
            addTrustManager(delegates, keyStore);
        } catch (Exception ex) {
            // Could not assemble the extra anchors: keep whatever trust sources were already added.
        }
    }

    private static void addTrustManager(List<X509TrustManager> delegates, KeyStore keyStore) {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
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
            throw last != null ? last : new CertificateException("No trust manager accepted the client certificate chain.");
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
            throw last != null ? last : new CertificateException("No trust manager accepted the server certificate chain.");
        }

        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> issuers = new ArrayList<X509Certificate>();
            for (X509TrustManager delegate : delegates) {
                X509Certificate[] certificates = delegate.getAcceptedIssuers();
                if (certificates != null) {
                    for (int i = 0; i < certificates.length; i++) {
                        issuers.add(certificates[i]);
                    }
                }
            }
            return issuers.toArray(new X509Certificate[issuers.size()]);
        }
    }
}
