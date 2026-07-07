package com.aresstack.askai.java8.net;

import java.net.InetSocketAddress;
import java.net.Proxy;

public final class ProxyConfiguration {

    private final boolean enabled;
    private final String host;
    private final int port;

    public ProxyConfiguration(boolean enabled, String host, int port) {
        this.enabled = enabled;
        this.host = host == null ? "" : host.trim();
        this.port = port;
    }

    public static ProxyConfiguration disabled() {
        return new ProxyConfiguration(false, "", 0);
    }

    public boolean isEnabled() {
        return enabled && host.length() > 0 && port > 0;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public Proxy toProxy() {
        if (!isEnabled()) {
            return Proxy.NO_PROXY;
        }
        return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
    }
}
