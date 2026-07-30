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

    /** Sanity bound: a hand-damaged focusAreaCount must not allocate unbounded lists. */
    private static final int MAXIMUM_FOCUS_AREAS = 1000;

    /**
     * Typed, VALIDATED load: only a missing file is a legitimate new project. Corruption, an
     * unsupported schema and a project-id mismatch are distinct failures the productive start
     * BLOCKS on - a damaged project never silently restarts as an empty research assignment.
     */
    public MetadataLoadResult load(String expectedProjectId) {
        if (!file.isFile()) {
            return MetadataLoadResult.missing();
        }
        Properties props = new Properties();
        try {
            props.load(new StringReader(StoreIo.readUtf8(file)));
        } catch (Exception unreadable) {
            return MetadataLoadResult.failed(MetadataLoadResult.Status.CORRUPT,
                    "project.properties is unreadable: " + unreadable.getMessage());
        }
        String projectId = props.getProperty("projectId");
        String question = props.getProperty("researchQuestion");
        if (projectId == null || question == null) {
            return MetadataLoadResult.failed(MetadataLoadResult.Status.CORRUPT,
                    "project.properties is missing its contract fields (projectId/researchQuestion)");
        }
        int schemaVersion;
        long revision;
        int count;
        try {
            schemaVersion = Integer.parseInt(props.getProperty("schemaVersion", "-1"));
            revision = Long.parseLong(props.getProperty("revision", "-1"));
            count = Integer.parseInt(props.getProperty("focusAreaCount", "0"));
        } catch (NumberFormatException broken) {
            return MetadataLoadResult.failed(MetadataLoadResult.Status.CORRUPT,
                    "project.properties carries non-numeric schema/revision/count fields");
        }
        if (schemaVersion != ResearchProjectMetadata.SCHEMA_VERSION) {
            return MetadataLoadResult.failed(MetadataLoadResult.Status.UNSUPPORTED_SCHEMA,
                    "schemaVersion " + schemaVersion + " is not supported (expected "
                            + ResearchProjectMetadata.SCHEMA_VERSION + ")");
        }
        if (expectedProjectId != null && !expectedProjectId.equals(projectId)) {
            return MetadataLoadResult.failed(MetadataLoadResult.Status.PROJECT_ID_MISMATCH,
                    "stored projectId '" + projectId + "' does not belong to project '"
                            + expectedProjectId + "'");
        }
        if (revision < 0 || count < 0 || count > MAXIMUM_FOCUS_AREAS) {
            return MetadataLoadResult.failed(MetadataLoadResult.Status.CORRUPT,
                    "revision/focusAreaCount out of range (revision=" + revision
                            + ", focusAreaCount=" + count + ")");
        }
        List<String> areas = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            String area = props.getProperty("focusArea." + i);
            if (area == null) {
                return MetadataLoadResult.failed(MetadataLoadResult.Status.CORRUPT,
                        "focusArea." + i + " is missing (focusAreaCount says " + count + ")");
            }
            areas.add(area);
        }
        return MetadataLoadResult.loaded(new ResearchProjectMetadata(schemaVersion, projectId,
                question, areas, revision));
    }
}
