package com.aresstack.askai.java8.net;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.concurrent.TimeUnit;

public final class PacUrlDiscoveryService {

    public static String defaultScript() {
        StringBuilder builder = new StringBuilder();
        builder.append("Option Explicit\r\n");
        builder.append("Dim shell\r\n");
        builder.append("Set shell = CreateObject(\"WScript.Shell\")\r\n");
        writeRegistryProbe(builder, "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\AutoConfigURL");
        writeRegistryProbe(builder, "HKCU\\Software\\Policies\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\AutoConfigURL");
        writeRegistryProbe(builder, "HKLM\\Software\\Policies\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\AutoConfigURL");
        writeRegistryProbe(builder, "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\AutoConfigURL");
        builder.append("WScript.Quit 1\r\n");
        return builder.toString();
    }

    private static void writeRegistryProbe(StringBuilder builder, String registryPath) {
        builder.append("PrintRegistryValue shell, \"").append(registryPath).append("\"\r\n");
        if (builder.indexOf("Sub PrintRegistryValue") < 0) {
            builder.append("Sub PrintRegistryValue(shell, registryPath)\r\n");
            builder.append("    Dim value\r\n");
            builder.append("    On Error Resume Next\r\n");
            builder.append("    Err.Clear\r\n");
            builder.append("    value = shell.RegRead(registryPath)\r\n");
            builder.append("    If Err.Number = 0 Then\r\n");
            builder.append("        WScript.Echo CStr(value)\r\n");
            builder.append("        WScript.Quit 0\r\n");
            builder.append("    End If\r\n");
            builder.append("    On Error GoTo 0\r\n");
            builder.append("End Sub\r\n");
        }
    }

    public String discoverWithScript(String script) throws IOException {
        String configuredScript = script == null || script.trim().length() == 0 ? defaultScript() : script;
        File directory = createWorkingDirectory();
        File scriptFile = new File(directory, "askai-pac-discovery.vbs");
        writeScript(scriptFile, configuredScript);
        return runWScriptAndReadFirstLine(scriptFile);
    }

    public String discoverWithWScript() throws IOException {
        return discoverWithScript(defaultScript());
    }

    private File createWorkingDirectory() throws IOException {
        File directory = new File(System.getProperty("java.io.tmpdir"), "askai-java8-proxy");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create temporary proxy directory: " + directory.getAbsolutePath());
        }
        return directory;
    }

    private void writeScript(File scriptFile, String script) throws IOException {
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(scriptFile), "UTF-8");
            writer.write(script);
            if (!script.endsWith("\n")) {
                writer.write("\r\n");
            }
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    private String runWScriptAndReadFirstLine(File scriptFile) throws IOException {
        Process process = new ProcessBuilder("wscript.exe", "//B", "//Nologo", scriptFile.getAbsolutePath()).redirectErrorStream(true).start();
        String output = readText(process);
        try {
            if (!process.waitFor(20L, TimeUnit.SECONDS)) {
                process.destroy();
                throw new IOException("WScript PAC discovery timed out.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("WScript PAC discovery was interrupted.", ex);
        }
        if (process.exitValue() != 0) {
            throw new IOException("WScript PAC discovery failed with exit code " + process.exitValue() + ": " + output);
        }
        String result = firstUsableLine(output);
        if (result == null) {
            throw new IOException("WScript PAC discovery did not print an AutoConfigURL.");
        }
        return result;
    }

    private String readText(Process process) throws IOException {
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

    private String firstUsableLine(String output) {
        if (output == null) {
            return null;
        }
        String[] lines = output.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (line.length() > 0 && line.toLowerCase().startsWith("http")) {
                return line;
            }
        }
        return null;
    }
}
