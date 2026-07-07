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

    public String discoverWithWScript() throws IOException {
        File directory = createWorkingDirectory();
        File scriptFile = new File(directory, "askai-pac-discovery.vbs");
        File outputFile = new File(directory, "askai-pac-discovery.txt");
        writeScript(scriptFile, outputFile);
        runWScript(scriptFile);
        String result = readFirstAutoConfigUrl(outputFile);
        if (result == null || result.trim().length() == 0) {
            throw new IOException("No AutoConfigURL found via WScript registry discovery. If AutoDetect=1 is used without AutoConfigURL, use a WPAD-capable mode instead.");
        }
        return result.trim();
    }

    private File createWorkingDirectory() throws IOException {
        File directory = new File(System.getProperty("java.io.tmpdir"), "askai-java8-proxy");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create temporary proxy directory: " + directory.getAbsolutePath());
        }
        return directory;
    }

    private void writeScript(File scriptFile, File outputFile) throws IOException {
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(scriptFile), "UTF-8");
            writer.write("Option Explicit\r\n");
            writer.write("Dim shell, fileSystem, outputFile\r\n");
            writer.write("Set shell = CreateObject(\"WScript.Shell\")\r\n");
            writer.write("Set fileSystem = CreateObject(\"Scripting.FileSystemObject\")\r\n");
            writer.write("Set outputFile = fileSystem.CreateTextFile(\"");
            writer.write(escapeForVbScript(outputFile.getAbsolutePath()));
            writer.write("\", True)\r\n");
            writeRegistryLine(writer, "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\AutoConfigURL");
            writeRegistryLine(writer, "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\AutoDetect");
            writeRegistryLine(writer, "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\ProxyEnable");
            writeRegistryLine(writer, "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\ProxyServer");
            writer.write("outputFile.WriteLine \"\"\r\n");
            writeRegistryLine(writer, "HKCU\\Software\\Policies\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\AutoConfigURL");
            writeRegistryLine(writer, "HKCU\\Software\\Policies\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\ProxyEnable");
            writeRegistryLine(writer, "HKCU\\Software\\Policies\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\ProxyServer");
            writer.write("outputFile.WriteLine \"\"\r\n");
            writeRegistryLine(writer, "HKLM\\Software\\Policies\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\AutoConfigURL");
            writeRegistryLine(writer, "HKLM\\Software\\Policies\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\ProxyEnable");
            writeRegistryLine(writer, "HKLM\\Software\\Policies\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\ProxyServer");
            writer.write("outputFile.WriteLine \"\"\r\n");
            writeRegistryLine(writer, "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\AutoConfigURL");
            writeRegistryLine(writer, "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\ProxyEnable");
            writeRegistryLine(writer, "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\ProxyServer");
            writer.write("outputFile.Close\r\n");
            writer.write("Sub WriteRegistryValue(outputFile, shell, registryPath)\r\n");
            writer.write("    Dim value\r\n");
            writer.write("    On Error Resume Next\r\n");
            writer.write("    Err.Clear\r\n");
            writer.write("    value = shell.RegRead(registryPath)\r\n");
            writer.write("    If Err.Number = 0 Then\r\n");
            writer.write("        outputFile.WriteLine registryPath & \" = \" & CStr(value)\r\n");
            writer.write("    Else\r\n");
            writer.write("        outputFile.WriteLine registryPath & \" = <not found>\"\r\n");
            writer.write("    End If\r\n");
            writer.write("    On Error GoTo 0\r\n");
            writer.write("End Sub\r\n");
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    private void writeRegistryLine(Writer writer, String path) throws IOException {
        writer.write("WriteRegistryValue outputFile, shell, \"");
        writer.write(path);
        writer.write("\"\r\n");
    }

    private void runWScript(File scriptFile) throws IOException {
        Process process = new ProcessBuilder("wscript.exe", "//B", "//Nologo", scriptFile.getAbsolutePath()).start();
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
            throw new IOException("WScript PAC discovery failed with exit code " + process.exitValue() + ".");
        }
    }

    private String readFirstAutoConfigUrl(File outputFile) throws IOException {
        if (!outputFile.isFile()) {
            throw new IOException("WScript PAC discovery did not create an output file.");
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(outputFile), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                String value = parseAutoConfigUrl(line);
                if (value != null) {
                    return value;
                }
            }
            return null;
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
    }

    private String parseAutoConfigUrl(String line) {
        if (line == null || line.indexOf("AutoConfigURL") < 0) {
            return null;
        }
        int separator = line.indexOf('=');
        if (separator < 0) {
            return null;
        }
        String value = line.substring(separator + 1).trim();
        if (value.length() == 0 || "<not found>".equalsIgnoreCase(value)) {
            return null;
        }
        return value;
    }

    private String escapeForVbScript(String value) {
        return value.replace("\"", "\"\"");
    }
}
