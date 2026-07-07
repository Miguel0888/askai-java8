package com.aresstack.askai.java8.net;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.regex.Pattern;

public final class Java8PacEvaluator {

    public String evaluate(String pacUrl, String targetUrl) throws IOException {
        if (isBlank(pacUrl)) {
            throw new IOException("PAC URL is empty.");
        }
        if (isBlank(targetUrl)) {
            throw new IOException("Target URL is empty.");
        }
        String pacScript = downloadPacScript(pacUrl.trim());
        return evaluateScript(pacScript, targetUrl.trim());
    }

    private String downloadPacScript(String pacUrl) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(pacUrl).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "askai-java8-pac");
        InputStream inputStream = null;
        try {
            int status = connection.getResponseCode();
            inputStream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            String body = readText(inputStream);
            if (status < 200 || status >= 300) {
                throw new IOException("PAC download failed with HTTP " + status + ": " + body);
            }
            if (body.trim().length() == 0) {
                throw new IOException("PAC download returned an empty file.");
            }
            return body;
        } finally {
            closeQuietly(inputStream);
            connection.disconnect();
        }
    }

    private String evaluateScript(String pacScript, String targetUrl) throws IOException {
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("JavaScript");
        if (engine == null) {
            engine = new ScriptEngineManager().getEngineByName("nashorn");
        }
        if (engine == null) {
            throw new IOException("No JavaScript engine available for PAC evaluation. Run AskAI with a Java 8 runtime or use a native Windows resolver mode.");
        }
        try {
            engine.put("__pacHelper", new PacHelper());
            engine.eval(helperScript());
            engine.eval(pacScript);
            Object result = ((Invocable) engine).invokeFunction("FindProxyForURL", targetUrl, hostOf(targetUrl));
            if (result == null) {
                throw new IOException("FindProxyForURL returned null.");
            }
            String proxyResult = String.valueOf(result).trim();
            if (proxyResult.length() == 0) {
                throw new IOException("FindProxyForURL returned an empty result.");
            }
            return proxyResult;
        } catch (ScriptException ex) {
            throw new IOException("PAC JavaScript evaluation failed: " + ex.getMessage(), ex);
        } catch (NoSuchMethodException ex) {
            throw new IOException("PAC file does not define FindProxyForURL(url, host).", ex);
        }
    }

    private String helperScript() {
        StringBuilder script = new StringBuilder();
        script.append("function isPlainHostName(host) { return String(host).indexOf('.') < 0; }\n");
        script.append("function dnsDomainIs(host, domain) { host=String(host).toLowerCase(); domain=String(domain).toLowerCase(); return host.length >= domain.length && host.substring(host.length-domain.length) === domain; }\n");
        script.append("function localHostOrDomainIs(host, hostdom) { host=String(host).toLowerCase(); hostdom=String(hostdom).toLowerCase(); return host === hostdom || (host.indexOf('.') < 0 && hostdom.indexOf(host + '.') === 0); }\n");
        script.append("function isResolvable(host) { return __pacHelper.isResolvable(String(host)); }\n");
        script.append("function dnsResolve(host) { return __pacHelper.dnsResolve(String(host)); }\n");
        script.append("function myIpAddress() { return __pacHelper.myIpAddress(); }\n");
        script.append("function dnsDomainLevels(host) { host=String(host); var n=0; for (var i=0;i<host.length;i++) { if (host.charAt(i)==='.') n++; } return n; }\n");
        script.append("function shExpMatch(str, shexp) { return __pacHelper.shExpMatch(String(str), String(shexp)); }\n");
        script.append("function isInNet(host, pattern, mask) { return __pacHelper.isInNet(String(host), String(pattern), String(mask)); }\n");
        script.append("function weekdayRange() { return true; }\n");
        script.append("function dateRange() { return true; }\n");
        script.append("function timeRange() { return true; }\n");
        script.append("function alert(message) { }\n");
        return script.toString();
    }

    private String hostOf(String targetUrl) throws IOException {
        try {
            String host = new URI(targetUrl).getHost();
            if (host == null || host.trim().length() == 0) {
                throw new IOException("Could not extract host from URL: " + targetUrl);
            }
            return host;
        } catch (Exception ex) {
            throw new IOException("Could not extract host from URL: " + targetUrl, ex);
        }
    }

    private String readText(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
    }

    private void closeQuietly(InputStream inputStream) {
        if (inputStream == null) {
            return;
        }
        try {
            inputStream.close();
        } catch (IOException ignored) {
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().length() == 0;
    }

    public static final class PacHelper {
        public boolean isResolvable(String host) {
            return dnsResolve(host).length() > 0;
        }

        public String dnsResolve(String host) {
            try {
                return InetAddress.getByName(host).getHostAddress();
            } catch (Exception ex) {
                return "";
            }
        }

        public String myIpAddress() {
            try {
                return InetAddress.getLocalHost().getHostAddress();
            } catch (Exception ex) {
                return "127.0.0.1";
            }
        }

        public boolean shExpMatch(String value, String shellExpression) {
            StringBuilder regex = new StringBuilder();
            for (int i = 0; i < shellExpression.length(); i++) {
                char c = shellExpression.charAt(i);
                if (c == '*') {
                    regex.append(".*");
                } else if (c == '?') {
                    regex.append('.');
                } else {
                    regex.append(Pattern.quote(String.valueOf(c)));
                }
            }
            return Pattern.matches(regex.toString(), value);
        }

        public boolean isInNet(String host, String pattern, String mask) {
            String address = dnsResolve(host);
            if (address.length() == 0) {
                address = host;
            }
            long addressValue = ipv4ToLong(address);
            long patternValue = ipv4ToLong(pattern);
            long maskValue = ipv4ToLong(mask);
            return (addressValue & maskValue) == (patternValue & maskValue);
        }

        private long ipv4ToLong(String value) {
            String[] parts = value.split("\\.");
            if (parts.length != 4) {
                return 0L;
            }
            long result = 0L;
            for (int i = 0; i < parts.length; i++) {
                int part;
                try {
                    part = Integer.parseInt(parts[i]);
                } catch (NumberFormatException ex) {
                    return 0L;
                }
                result = (result << 8) + (part & 0xff);
            }
            return result;
        }
    }
}
