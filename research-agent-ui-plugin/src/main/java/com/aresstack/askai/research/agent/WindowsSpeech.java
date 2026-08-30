package com.aresstack.askai.research.agent;

/**
 * Read-aloud V1 over the WINDOWS speech synthesizer (SAPI via {@code System.Speech}) — no model
 * involved: the text LLM cannot produce audio, the OS voices can, immediately. One utterance at a
 * time: {@link #speak} stops whatever is playing first; {@link #stop} kills the process. The text
 * travels over STDIN (UTF-8, explicitly negotiated), so quoting and umlauts survive. A later slice
 * may add a configurable output MODEL in the settings — this class is the seam to swap behind.
 */
final class WindowsSpeech {

    private Process process;

    /** Speak with the system default voice; see {@link #speak(String, String)}. */
    synchronized void speak(String markdown) {
        speak(markdown, null);
    }

    /**
     * Speak this text (markdown allowed — it is flattened first); replaces a running utterance.
     * With a language code the synthesizer PICKS AN INSTALLED VOICE OF THAT LANGUAGE (Windows
     * usually ships e.g. de-DE and en-US voices side by side) — so English text is no longer read
     * with a German accent. When no voice of that culture exists, the default voice speaks anyway.
     */
    synchronized void speak(String markdown, String languageCode) {
        stop();
        String text = plainTextForSpeech(markdown);
        if (text.isEmpty()) {
            return;
        }
        String culture = cultureFor(languageCode);
        String selectVoice = culture == null ? "" :
                "try { $s.SelectVoiceByHints([System.Speech.Synthesis.VoiceGender]::NotSet, "
                        + "[System.Speech.Synthesis.VoiceAge]::NotSet, 0, "
                        + "(New-Object System.Globalization.CultureInfo('" + culture + "'))) } "
                        + "catch { }; "; // no voice of that culture installed → default voice
        try {
            ProcessBuilder builder = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "[Console]::InputEncoding = New-Object System.Text.UTF8Encoding $false; "
                            + "Add-Type -AssemblyName System.Speech; "
                            + "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                            + selectVoice
                            + "$s.Speak([Console]::In.ReadToEnd())");
            builder.redirectErrorStream(true);
            process = builder.start();
            java.io.OutputStream stdin = process.getOutputStream();
            stdin.write(text.getBytes("UTF-8"));
            stdin.close();
        } catch (java.io.IOException cannotStart) {
            System.err.println("[read-aloud] Windows speech unavailable: "
                    + cannotStart.getMessage());
            process = null;
        }
    }

    /**
     * @return the Windows culture for an ISO-639-1 code, or null for unknown codes (default
     *         voice). A FIXED map — the value is embedded in a PowerShell command, so only known
     *         constants may ever pass through.
     */
    static String cultureFor(String languageCode) {
        if ("de".equals(languageCode)) {
            return "de-DE";
        }
        if ("en".equals(languageCode)) {
            return "en-US";
        }
        return null;
    }

    /** Stop the current utterance (no-op when silent). */
    synchronized void stop() {
        if (process != null) {
            process.destroy();
            process = null;
        }
    }

    /**
     * Flatten markdown into speakable text: code fences and inline code dropped, links reduced to
     * their label, list/heading/emphasis markers removed. Deliberately rough — the voice does not
     * need typography, it needs sentences.
     */
    static String plainTextForSpeech(String markdown) {
        if (markdown == null) {
            return "";
        }
        String text = markdown;
        text = text.replaceAll("(?s)```.*?```", " "); // fenced code says nothing aloud
        text = text.replaceAll("`([^`]*)`", "$1");
        text = text.replaceAll("!?\\[([^\\]]*)\\]\\([^)]*\\)", "$1"); // links/images → label
        text = text.replaceAll("(?m)^#{1,6}\\s*", "");
        text = text.replaceAll("(?m)^\\s*[-*+]\\s+", "");
        text = text.replaceAll("(?m)^\\s*>\\s?", "");
        text = text.replaceAll("[*_~]{1,3}", "");
        text = text.replaceAll("\\s+", " ").trim();
        return text;
    }
}
