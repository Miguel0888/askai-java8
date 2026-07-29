package com.aresstack.askai.research.store;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Persists {@link ResearchProjectMetadata} as {@code project.properties} in the project root —
 * written atomically (temp file → flush → atomic replace via {@link StoreIo}). A missing or corrupt
 * file loads as {@code null} (no restored assignment), never as a guessed one.
 */
public final class ResearchProjectMetadataStore {

    private final File file;

    public ResearchProjectMetadataStore(File projectRoot) {
        this.file = new File(projectRoot, "project.properties");
    }

    public void save(ResearchProjectMetadata metadata) throws IOException {
        Properties props = new Properties();
        props.setProperty("schemaVersion", String.valueOf(metadata.getSchemaVersion()));
        props.setProperty("projectId", metadata.getProjectId());
        props.setProperty("researchQuestion", metadata.getResearchQuestion());
        props.setProperty("revision", String.valueOf(metadata.getRevision()));
        List<String> areas = metadata.getConfirmedFocusAreas();
        props.setProperty("focusAreaCount", String.valueOf(areas.size()));
        for (int i = 0; i < areas.size(); i++) {
            props.setProperty("focusArea." + i, areas.get(i));
        }
        StringWriter writer = new StringWriter();
        props.store(writer, "AskAI research project metadata");
        StoreIo.atomicWrite(file, writer.toString());
    }

    /** @return the restored metadata, or {@code null} when the file is missing or unreadable. */
    public ResearchProjectMetadata load() {
        if (!file.isFile()) {
            return null;
        }
        try {
            Properties props = new Properties();
            props.load(new StringReader(StoreIo.readUtf8(file)));
            String projectId = props.getProperty("projectId");
            String question = props.getProperty("researchQuestion");
            if (projectId == null || question == null) {
                return null; // corrupt: the contract fields are missing
            }
            int count = Integer.parseInt(props.getProperty("focusAreaCount", "0"));
            List<String> areas = new ArrayList<String>();
            for (int i = 0; i < count; i++) {
                String area = props.getProperty("focusArea." + i);
                if (area != null) {
                    areas.add(area);
                }
            }
            return new ResearchProjectMetadata(
                    Integer.parseInt(props.getProperty("schemaVersion",
                            String.valueOf(ResearchProjectMetadata.SCHEMA_VERSION))),
                    projectId, question, areas,
                    Long.parseLong(props.getProperty("revision", "0")));
        } catch (Exception corrupt) {
            return null;
        }
    }
}
