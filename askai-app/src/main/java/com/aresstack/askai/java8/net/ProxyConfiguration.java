package com.aresstack.askai.java8.net;

import com.aresstack.winproxy.ProxyDefaults;
import com.aresstack.winproxy.ProxyMode;
import com.aresstack.winproxy.WindowsProxyResolver;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Proxy;

public final class ProxyConfiguration {

    private final ProxyMode mode;
    private final String testUrl;
    private final String pacUrlDiscoveryScript;
    private final String pacUrl;
    private final String manualProxyHost;
    private final int manualProxyPort;

    public ProxyConfiguration(boolean enabled, String host, int port) {
        this(enabled ? ProxyMode.MANUAL_PROXY : ProxyMode.DISABLED,
                defaults().getTestUrl(),
                defaults().getPacUrlDiscoveryScript(),
                "",
                host,
                port);
    }

    public ProxyConfiguration(ProxyMode mode, String testUrl, String pacUrlDiscoveryScript,
                              String pacUrl, String manualProxyHost, int manualProxyPort) {
        this.mode = mode == null ? ProxyMode.PAC_URL_POWERSHELL : mode;
        this.testUrl = nonBlank(testUrl, com.aresstack.winproxy.ProxyConfiguration.defaults().getTestUrl());
        this.pacUrlDiscoveryScript = nonBlank(pacUrlDiscoveryScript, ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT);
        this.pacUrl = empty(pacUrl);
        this.manualProxyHost = empty(manualProxyHost);
        this.manualProxyPort = manualProxyPort;
    }

    public static ProxyConfiguration disabled() {
        return new ProxyConfiguration(ProxyMode.DISABLED,
                com.aresstack.winproxy.ProxyConfiguration.defaults().getTestUrl(),
                ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT,
                "",
                "",
                0);
    }

    public static ProxyConfiguration defaults() {
        com.aresstack.winproxy.ProxyConfiguration defaults = com.aresstack.winproxy.ProxyConfiguration.defaults();
        return new ProxyConfiguration(defaults.getMode(),
                defaults.getTestUrl(),
                defaultPacScript(defaults),
                defaults.getPacUrl(),
                defaults.getManualProxyHost(),
                defaults.getManualProxyPort());
    }

    public boolean isEnabled() { return mode != ProxyMode.DISABLED; }
    public ProxyMode getMode() { return mode; }
    public String getModeName() { return mode.name(); }
    public String getTestUrl() { return testUrl; }
    public String getPacUrlDiscoveryScript() { return pacUrlDiscoveryScript; }
    public String getPacUrl() { return manualPacUrl(); }
    public String getHost() { return manualProxyHost; }
    public String getManualProxyHost() { return manualProxyHost; }
    public int getPort() { return manualProxyPort; }
    public int getManualProxyPort() { return manualProxyPort; }

    public com.aresstack.winproxy.ProxyConfiguration toWinProxyConfiguration() {
        return toWinProxyConfiguration(mode, manualPacUrl());
    }

    private com.aresstack.winproxy.ProxyConfiguration toWinProxyConfiguration(ProxyMode configuredMode, String effectivePacUrl) {
        com.aresstack.winproxy.ProxyConfiguration.Builder builder = com.aresstack.winproxy.ProxyConfiguration.builder()
                .mode(configuredMode)
                .testUrl(testUrl)
                .pacUrlDiscoveryScript(pacUrlDiscoveryScript);
        if (trimToNull(effectivePacUrl) != null) {
            builder.pacUrl(effectivePacUrl);
        }
        if (trimToNull(manualProxyHost) != null) {
            builder.manualProxyHost(manualProxyHost);
        }
        if (manualProxyPort > 0) {
            builder.manualProxyPort(manualProxyPort);
        }
        return builder.build();
    }

    public Object resolve(String url) {
        return resolveWithConfiguration(toWinProxyConfiguration(), url);
    }

    public Proxy toProxy() {
        return toProxyFor(testUrl);
    }

    public Proxy toProxyFor(String url) {
        try {
            return resolveJavaProxy(url);
        } catch (IOException ex) {
            return Proxy.NO_PROXY;
        }
    }

    public Proxy resolveJavaProxy(String url) throws IOException {
        validateForUse();
        if (mode == ProxyMode.DISABLED) {
            return Proxy.NO_PROXY;
        }
        if (mode == ProxyMode.MANUAL_PROXY) {
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(manualProxyHost, manualProxyPort));
        }
        Object result = resolveWithFallback(nonBlank(url, testUrl));
        String text = result == null ? "" : String.valueOf(result);
        if (text.toUpperCase().startsWith("ERROR")) {
            throw new IOException("Proxy resolution failed: " + text);
        }
        if (text.toUpperCase().indexOf("DIRECT") >= 0) {
            return Proxy.NO_PROXY;
        }
        Proxy reflected = reflectProxy(result);
        if (reflected != null) {
            return reflected;
        }
        Proxy parsed = parseProxy(text);
        if (parsed != null) {
            return parsed;
        }
        return Proxy.NO_PROXY;
    }

    private Object resolveWithFallback(String url) throws IOException {
        Object first;
        try {
            first = resolveWithConfiguration(toWinProxyConfiguration(), url);
        } catch (RuntimeException ex) {
            if (!isWScriptFallbackMode()) {
                throw new IOException("Proxy resolution failed: " + ex.getMessage(), ex);
            }
            return resolveWithDiscoveredPacUrl(url, ex.getMessage());
        }
        String text = first == null ? "" : String.valueOf(first);
        if (text.toUpperCase().startsWith("ERROR") && isWScriptFallbackMode()) {
            return resolveWithDiscoveredPacUrl(url, text);
        }
        return first;
    }

    private Object resolveWithDiscoveredPacUrl(String url, String previousFailure) throws IOException {
        String discoveredPacUrl;
        try {
            discoveredPacUrl = new PacUrlDiscoveryService().discoverWithWScript();
        } catch (IOException ex) {
            throw new IOException("Proxy resolution failed: " + previousFailure + "; WScript discovery also failed: " + ex.getMessage(), ex);
        }
        com.aresstack.winproxy.ProxyConfiguration discoveredConfiguration =
                toWinProxyConfiguration(ProxyMode.PAC_URL_MANUAL, discoveredPacUrl);
        Object second = resolveWithConfiguration(discoveredConfiguration, url);
        String text = second == null ? "" : String.valueOf(second);
        if (text.toUpperCase().startsWith("ERROR")) {
            throw new IOException("Proxy resolution failed after WScript PAC discovery (" + discoveredPacUrl + "): " + text);
        }
        return second;
    }

    private boolean isWScriptFallbackMode() {
        return mode == ProxyMode.PAC_URL_POWERSHELL || mode == ProxyMode.PAC_URL_WINDOWS_SETTINGS;
    }

    private Object resolveWithConfiguration(com.aresstack.winproxy.ProxyConfiguration configuration, String url) {
        return new WindowsProxyResolver(configuration).resolve(url);
    }

    public void validateForUse() throws IOException {
        if (mode == ProxyMode.DISABLED) {
            return;
        }
        if (mode == ProxyMode.MANUAL_PROXY) {
            if (trimToNull(manualProxyHost) == null || manualProxyPort <= 0) {
                throw new IOException("MANUAL_PROXY requires manual host and port.");
            }
            return;
        }
        if (mode == ProxyMode.PAC_URL_MANUAL && trimToNull(pacUrlDiscoveryScript) == null) {
            throw new IOException("PAC_URL_MANUAL requires the PAC/WPAD URL in the PAC URL discovery script field, e.g. http://wpad/wpad.dat or the AutoConfigURL from Windows settings.");
        }
    }

    private String manualPacUrl() {
        if (mode == ProxyMode.PAC_URL_MANUAL) {
            return pacUrlDiscoveryScript;
        }
        return pacUrl;
    }

    private Proxy reflectProxy(Object result) {
        if (result == null) {
            return null;
        }
        String[] proxyMethods = new String[]{"toProxy", "getProxy", "proxy"};
        for (int i = 0; i < proxyMethods.length; i++) {
            try {
                Method method = result.getClass().getMethod(proxyMethods[i]);
                Object value = method.invoke(result);
                if (value instanceof Proxy) {
                    return (Proxy) value;
                }
            } catch (Exception ignored) {
            }
        }
        String host = stringMethod(result, "getHost", "host", "getProxyHost", "proxyHost");
        Integer port = intMethod(result, "getPort", "port", "getProxyPort", "proxyPort");
        if (host != null && host.length() > 0 && port != null && port.intValue() > 0) {
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port.intValue()));
        }
        return null;
    }

    private String stringMethod(Object target, String first, String second, String third, String fourth) {
        String[] names = new String[]{first, second, third, fourth};
        for (int i = 0; i < names.length; i++) {
            try {
                Method method = target.getClass().getMethod(names[i]);
                Object value = method.invoke(target);
                if (value != null) {
                    return String.valueOf(value);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private Integer intMethod(Object target, String first, String second, String third, String fourth) {
        String[] names = new String[]{first, second, third, fourth};
        for (int i = 0; i < names.length; i++) {
            try {
                Method method = target.getClass().getMethod(names[i]);
                Object value = method.invoke(target);
                if (value instanceof Number) {
                    return Integer.valueOf(((Number) value).intValue());
                }
                if (value != null) {
                    return Integer.valueOf(String.valueOf(value));
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private Proxy parseProxy(String value) {
        if (value == null || value.toUpperCase().indexOf("DIRECT") >= 0) {
            return Proxy.NO_PROXY;
        }
        String[] parts = value.split("[ ,;()]+");
        for (int i = 0; i < parts.length; i++) {
            String token = parts[i];
            int colon = token.lastIndexOf(':');
            if (colon > 0 && colon + 1 < token.length()) {
                String host = token.substring(0, colon).replace("/", "").trim();
                String portText = token.substring(colon + 1).trim();
                try {
                    int port = Integer.parseInt(portText);
                    if (host.length() > 0 && port > 0) {
                        return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    private static String defaultPacScript(com.aresstack.winproxy.ProxyConfiguration cfg) {
        return nonBlank(cfg.getPacUrlDiscoveryScript(), ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT);
    }

    private static String nonBlank(String value, String fallback) {
        String trimmed = trimToNull(value);
        return trimmed == null ? fallback : trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() == 0 ? null : trimmed;
    }

    private static String empty(String value) {
        return value == null ? "" : value;
    }
}
