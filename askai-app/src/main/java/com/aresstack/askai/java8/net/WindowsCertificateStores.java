package com.aresstack.askai.java8.net;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Reads X.509 certificates from the Windows certificate stores that Java's {@code SunMSCAPI}
 * provider does <em>not</em> expose.
 *
 * <p>{@code SunMSCAPI} only offers {@code Windows-ROOT} (Trusted Root Certification Authorities) and
 * {@code Windows-MY} (Personal). It has <strong>no</strong> access to the Windows
 * <em>Intermediate Certification Authorities</em> store. Corporate TLS-intercepting proxies present
 * a leaf certificate signed by an intermediate CA whose root is trusted in {@code Windows-ROOT}, but
 * the intermediate itself lives in the intermediate store and is frequently <em>not</em> sent in the
 * TLS handshake. Windows/SChannel (browsers, {@code curl}) build the chain by pulling the
 * intermediate from that store, but Java's PKIX validator only has the server-sent certificates plus
 * the {@code Windows-ROOT} anchors, so path building fails with
 * {@code unable to find valid certification path to requested target}.</p>
 *
 * <p>To close that gap this helper enumerates the Root <em>and</em> Intermediate CA stores (both the
 * {@code LocalMachine} and {@code CurrentUser} scopes, which include GPO/enterprise-pushed CAs) via
 * PowerShell and returns their certificates. Adding those certificates as trust anchors lets PKIX
 * build the chain even when the proxy omits the intermediate. Only certificates the operating system
 * already trusts are returned, so certificate validation is not weakened.</p>
 *
 * <p>On non-Windows platforms (or when PowerShell is unavailable) an empty list is returned.</p>
 */
final class WindowsCertificateStores {

    private static final long TIMEOUT_SECONDS = 25L;

    private WindowsCertificateStores() {
    }

    /**
     * @return the certificates found in the Windows Root and Intermediate CA stores, or an empty list
     *         when they cannot be read (for example on non-Windows platforms).
     */
    static List<X509Certificate> loadRootAndIntermediateCertificates() {
        if (!isWindows()) {
            return new ArrayList<X509Certificate>();
        }
        try {
            String output = runPowerShell(buildScript());
            return parseCertificates(output);
        } catch (Exception ex) {
            // PowerShell unavailable or failed: fall back to whatever SunMSCAPI already provides.
            return new ArrayList<X509Certificate>();
        }
    }

    private static boolean isWindows() {
        String osName = System.getProperty("os.name", "");
        return osName.toLowerCase().contains("win");
    }

    /**
     * PowerShell script that prints each certificate as a single line of Base64-encoded DER,
     * gathered from the Root and Intermediate CA stores in both machine and user scopes.
     */
    private static String buildScript() {
        StringBuilder builder = new StringBuilder();
        builder.append("$ErrorActionPreference = 'SilentlyContinue'\r\n");
        builder.append("$stores = @('Cert:\\LocalMachine\\CA','Cert:\\CurrentUser\\CA',");
        builder.append("'Cert:\\LocalMachine\\Root','Cert:\\CurrentUser\\Root')\r\n");
        builder.append("foreach ($store in $stores) {\r\n");
        builder.append("    Get-ChildItem -Path $store | ForEach-Object {\r\n");
        builder.append("        [Convert]::ToBase64String($_.RawData)\r\n");
        builder.append("    }\r\n");
        builder.append("}\r\n");
        return builder.toString();
    }

    private static String runPowerShell(String script) throws IOException {
        File directory = new File(System.getProperty("java.io.tmpdir"), "askai-java8-proxy");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create temporary directory: " + directory.getAbsolutePath());
        }
        File scriptFile = new File(directory, "askai-windows-ca-export.ps1");
        writeScript(scriptFile, script);

        Process process = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-File", scriptFile.getAbsolutePath())
                .redirectErrorStream(true)
                .start();
        boolean completed;
        try {
            completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroy();
            throw new IOException("Reading Windows certificate stores was interrupted.", ex);
        }
        if (!completed) {
            process.destroy();
            throw new IOException("Reading Windows certificate stores timed out.");
        }
        return readText(process);
    }

    private static void writeScript(File scriptFile, String script) throws IOException {
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(scriptFile), "UTF-8");
            writer.write(script);
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    private static String readText(Process process) throws IOException {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
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

    /**
     * Parses the PowerShell output, one Base64-encoded DER certificate per line, de-duplicating
     * repeated certificates (the machine and user scopes overlap heavily).
     */
    private static List<X509Certificate> parseCertificates(String output) throws CertificateException {
        List<X509Certificate> certificates = new ArrayList<X509Certificate>();
        if (output == null || output.trim().length() == 0) {
            return certificates;
        }
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>();
        String[] lines = output.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (line.length() == 0 || seen.containsKey(line)) {
                continue;
            }
            seen.put(line, Boolean.TRUE);
            byte[] der = decodeBase64(line);
            if (der == null) {
                continue;
            }
            try {
                Object certificate = factory.generateCertificate(new ByteArrayInputStream(der));
                if (certificate instanceof X509Certificate) {
                    certificates.add((X509Certificate) certificate);
                }
            } catch (CertificateException ex) {
                // Skip anything that is not a parseable X.509 certificate.
            }
        }
        return certificates;
    }

    private static byte[] decodeBase64(String value) {
        try {
            return java.util.Base64.getMimeDecoder().decode(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
