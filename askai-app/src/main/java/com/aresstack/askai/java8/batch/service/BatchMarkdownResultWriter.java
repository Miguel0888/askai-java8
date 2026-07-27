package com.aresstack.askai.java8.batch.service;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/** Append one model/profile transcription section to the source-adjacent Markdown file. */
public final class BatchMarkdownResultWriter {

    public synchronized File append(File audioFile, String modelName,
                                    String profileName, String transcription) throws IOException {
        File markdownFile = markdownFileFor(audioFile);
        boolean needsSeparator = markdownFile.isFile() && markdownFile.length() > 0L;
        Writer writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(markdownFile, true), StandardCharsets.UTF_8));
        try {
            if (needsSeparator) writer.write(System.lineSeparator() + System.lineSeparator());
            writer.write("# " + sanitizeHeading(modelName) + System.lineSeparator());
            writer.write(System.lineSeparator());
            writer.write("## Audio profile: " + sanitizeHeading(profileName) + System.lineSeparator());
            writer.write(System.lineSeparator());
            writer.write(transcription == null ? "" : transcription.trim());
            writer.write(System.lineSeparator());
        } finally {
            writer.close();
        }
        return markdownFile;
    }

    public File markdownFileFor(File audioFile) {
        String name = audioFile.getName();
        int extension = name.lastIndexOf('.');
        String baseName = extension > 0 ? name.substring(0, extension) : name;
        return new File(audioFile.getParentFile(), baseName + ".md");
    }

    private String sanitizeHeading(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
