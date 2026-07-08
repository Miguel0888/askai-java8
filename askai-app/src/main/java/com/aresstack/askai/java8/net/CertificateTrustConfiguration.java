package com.aresstack.askai.java8.net;

public final class CertificateTrustConfiguration {

    private final boolean useJvmDefaultTrustStore;
    private final boolean useWindowsRootStore;
    private final boolean useWindowsCaStores;

    public CertificateTrustConfiguration(boolean useJvmDefaultTrustStore,
                                         boolean useWindowsRootStore,
                                         boolean useWindowsCaStores) {
        this.useJvmDefaultTrustStore = useJvmDefaultTrustStore;
        this.useWindowsRootStore = useWindowsRootStore;
        this.useWindowsCaStores = useWindowsCaStores;
    }

    public static CertificateTrustConfiguration defaults() {
        return new CertificateTrustConfiguration(true, true, true);
    }

    public boolean isUseJvmDefaultTrustStore() {
        return useJvmDefaultTrustStore;
    }

    public boolean isUseWindowsRootStore() {
        return useWindowsRootStore;
    }

    public boolean isUseWindowsCaStores() {
        return useWindowsCaStores;
    }

    public String key() {
        return String.valueOf(useJvmDefaultTrustStore) + ':'
                + String.valueOf(useWindowsRootStore) + ':'
                + String.valueOf(useWindowsCaStores);
    }

    public String describe() {
        return "JVM default=" + useJvmDefaultTrustStore
                + ", Windows-ROOT=" + useWindowsRootStore
                + ", Windows CA stores=" + useWindowsCaStores;
    }
}
