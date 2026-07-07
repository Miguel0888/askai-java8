package com.aresstack.askai.java8.net;

import com.aresstack.winproxy.ProxyDefaults;
import com.aresstack.winproxy.ProxyMode;
import com.aresstack.winproxy.WindowsProxyResolver;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Proxy;

public final class ProxyConfiguration {

    public static final String DISABLED = "DISABLED";
    public static final String MANUAL_PROXY = "MANUAL_PROXY";
    public static final String WINDOWS_STATIC_PROXY = "WINDOWS_STATIC_PROXY";
    public static final String PAC_URL_MANUAL = "PAC_URL_MANUAL";
    public static final String PAC_URL_POWERSHELL = "PAC_URL_POWERSHELL";
    public static final String PAC_URL_WSCRIPT = "PAC_URL_WSCRIPT";
    public static final String PAC_URL_WINDOWS_SETTINGS = "PAC_URL_WINDOWS_SETTINGS";
    public static final String WINDOWS_NATIVE_PROXY_SETTINGS = "WINDOWS_NATIVE_PROXY_SETTINGS";
    public static final String WINDOWS_NATIVE_ROUTE_RESOLVER = "WINDOWS_NATIVE_ROUTE_RESOLVER";

    private final String mode;
    private final String testUrl;
    private final String pacUrlDiscoveryScript;
    private final String pacUrl;
    private final String manualProxyHost;
    private final int manualProxyPort;

    public ProxyConfiguration(boolean enabled, String host, int port) {
        this(enabled ? MANUAL_PROXY : DISABLED,
                defaults().getTestUrl(),
                defaults().getPacUrlDiscoveryScript(),
                "",
                host,
                port);
    }

    public ProxyConfiguration(String mode, String testUrl, String pacUrlDiscoveryScript,
                              String pacUrl, String manualProxyHost, int manualProxyPort) {
        this.mode = normalizeMode(mode);
        this.testUrl = nonBlank(testUrl, com.aresstack.winproxy.ProxyConfiguration.defaults().getTestUrl());
        this.pacUrlDiscoveryScript = nonBlank(pacUrlDiscoveryScript, defaultDiscoveryScript(this.mode));
        this.pacUrl = empty(pacUrl);
        this.manualProxyHost = empty(manualProxyHost);
        this.manualProxyPort = manualProxyPort;
    }

    public ProxyConfiguration(ProxyMode mode, String testUrl, String pacUrlDiscoveryScript,
                              String pacUrl, String manualProxyHost, int manualProxyPort) {
        this(mode == null ? null : mode.name(), testUrl, pacUrlDiscoveryScript, pacUrl, manualProxyHost, manualProxyPort);
    }

    public static ProxyConfiguration disabled() {
        return new ProxyConfiguration(DISABLED,
                com.aresstack.winproxy.ProxyConfiguration.defaults().getTestUrl(),
                ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT,
                "",
                "",
                0);
    }

    public static ProxyConfiguration defaults() {
        com.aresstack.winproxy.ProxyConfiguration defaults = com.aresstack.winproxy.ProxyConfiguration.defaults();
        return new ProxyConfiguration(defaults.getMode().name(),
                defaults.getTestUrl(),
                defaultPacScript(defaults),
                defaults.getPacUrl(),
                defaults.getManualProxyHost(),
                defaults.getManualProxyPort());
    }

    public boolean isEnabled() { return !DISABLED.equals(mode); }
    public String getMode() { return mode; }
    public String getModeName() { return mode; }
    public String getTestUrl() { return testUrl; }
    public String getPacUrlDiscoveryScript() { return pacUrlDiscoveryScript; }
    public String getPacUrl() { return manualPacUrl(); }
    public String getHost() { return manualProxyHost; }
    public String getManualProxyHost() { return manualProxyHost; }
    public int getPort() { return manualProxyPort; }
    public int getManualProxyPort() { return manualProxyPort; }

    public com.aresstack.winproxy.ProxyConfiguration toWinProxyConfiguration() {
        return toWinProxyConfiguration(toWinProxyMode(mode), manualPacUrl());
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
        try {
            return resolveByMode(nonBlank(url, testUrl));
        } catch (IOException ex) {
            return "ERROR (" + ex.getMessage() + ")";
        }
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
        if (DISABLED.equals(mode)) {
            return Proxy.NO_PROXY;
        }
        if (MANUAL_PROXY.equals(mode)) {
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(manualProxyHost, manualProxyPort));
        }
        Object result = resolveByMode(nonBlank(url, testUrl));
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

    private Object resolveByMode(String url) throws IOException {
        if (PAC_URL_WSCRIPT.equals(mode)) {
            String discoveredPacUrl = new PacUrlDiscoveryService().discoverWithScript(pacUrlDiscoveryScript);
            return resolveWithConfiguration(toWinProxyConfiguration(ProxyMode.PAC_URL_MANUAL, discoveredPacUrl), url);
        }
        return resolveWithConfiguration(toWinProxyConfiguration(), url);
    }

    private Object resolveWithConfiguration(com.aresstack.winproxy.ProxyConfiguration configuration, String url) {
        return new WindowsProxyResolver(configuration).resolve(url);
    }

    public void validateForUse() throws IOException {
        if (DISABLED.equals(mode)) {
            return;
        }
        if (MANUAL_PROXY.equals(mode)) {
            if (trimToNull(manualProxyHost) == null || manualProxyPort <= 0) {
                throw new IOException("MANUAL_PROXY requires manual host and port.");
            }
            return;
        }
        if (PAC_URL_MANUAL.equals(mode) && trimToNull(pacUrlDiscoveryScript) == null) {
            throw new IOException("PAC_URL_MANUAL requires the PAC/WPAD URL in the PAC URL discovery script field.");
        }
        if (PAC_URL_WSCRIPT.equals(mode) && trimToNull(pacUrlDiscoveryScript) == null) {
            throw new IOException("PAC_URL_WSCRIPT requires a WScript discovery script in the PAC URL discovery script field.");
        }
    }

    private String manualPacUrl() {
        if (PAC_URL_MANUAL.equals(mode)) {
            return pacUrlDiscoveryScript;
        }
        return pacUrl;
    }

    private ProxyMode toWinProxyMode(String configuredMode) {
        if (PAC_URL_WSCRIPT.equals(configuredMode)) {
            return ProxyMode.PAC_URL_MANUAL;
        }
        return ProxyMode.valueOf(configuredMode);
    }

    private static String normalizeMode(String value) {
        String candidate = value == null || value.trim().length() == 0 ? PAC_URL_POWERSHELL : value.trim();
        if (PAC_URL_WSCRIPT.equals(candidate)) {
            return candidate;
        }
        try {
            return ProxyMode.valueOf(candidate).name();
        } catch (Exception ex) {
            return PAC_URL_POWERSHELL;
        }
    }

    private static String defaultDiscoveryScript(String configuredMode) {
        if (PAC_URL_WSCRIPT.equals(configuredMode)) {
            return PacUrlDiscoveryService.defaultScript();
        }
        return ProxyDefaults.DEFAULT_PAC_URL_DISCOVERY_SCRIPT;
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
