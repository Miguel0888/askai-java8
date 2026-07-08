package com.aresstack.askai.java8.net;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
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

final class WindowsCertificateStores {

    private static final long TIMEOUT_SECONDS = 25L;

    private WindowsCertificateStores() {
    }

    static List<X509Certificate> loadRootAndIntermediateCertificates() {
        if (!isWindows()) {
            return new ArrayList<X509Certificate>();
        }
        try {
            String output = runPowerShell(buildScript());
            return parseCertificates(output);
        } catch (Exception ex) {
            return new ArrayList<X509Certificate>();
        }
    }

    private static boolean isWindows() {
        String osName = System.getProperty("os.name", "");
        return osName.toLowerCase().contains("win");
    }

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
        File outputFile = new File(directory, "askai-windows-ca-export.txt");
        writeScript(scriptFile, script);
        if (outputFile.isFile() && !outputFile.delete()) {
            throw new IOException("Could not replace temporary certificate export file: " + outputFile.getAbsolutePath());
        }

        Process process = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-File", scriptFile.getAbsolutePath())
                .redirectErrorStream(true)
                .redirectOutput(outputFile)
                .start();
        boolean completed;
        try {
            completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            destroy(process);
            throw new IOException("Reading Windows certificate stores was interrupted.", ex);
        }
        if (!completed) {
            destroy(process);
            throw new IOException("Reading Windows certificate stores timed out.");
        }
        return readText(outputFile);
    }

    private static void destroy(Process process) {
        try {
            process.destroyForcibly();
        } catch (Throwable ignored) {
            process.destroy();
        }
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

    private static String readText(File file) throws IOException {
        if (!file.isFile()) {
            return "";
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
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
