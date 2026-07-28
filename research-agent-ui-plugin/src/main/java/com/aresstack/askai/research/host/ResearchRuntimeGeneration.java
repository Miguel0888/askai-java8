package com.aresstack.askai.research.host;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One published generation of the productive research runtime. A generation owns its factory (and with it the
 * endpoint ids {@code *.g<id>} and every process its sessions spawn). {@link #retire()} first LOCKS the
 * generation for new sessions, then closes every open session's resources in order — a retired generation can
 * never accept work again (its endpoints/tokens are gone).
 */
public final class ResearchRuntimeGeneration {

    private final long id;
    private final ProductiveResearchBackendFactory factory;
    private final List<ProductiveResearchSessionResources> openSessions =
            new CopyOnWriteArrayList<ProductiveResearchSessionResources>();
    private volatile boolean retired;

    ResearchRuntimeGeneration(long id, ProductiveResearchBackendFactory factory) {
        this.id = id;
        this.factory = factory;
    }

    public long getId() {
        return id;
    }

    public boolean isRetired() {
        return retired;
    }

    /** @throws IllegalStateException when the generation is retired (locked for new sessions). */
    public ProductiveResearchSessionResources createSession(String sessionKey, File projectDir)
            throws IOException {
        if (retired) {
            throw new IllegalStateException("Generation g" + id + " is retired; new sessions are locked.");
        }
        ProductiveResearchSessionResources resources = factory.createSession(sessionKey, projectDir);
        if (retired) { // raced with retire(): never leak resources of a retired generation
            resources.close();
            throw new IllegalStateException("Generation g" + id + " was retired during session start.");
        }
        openSessions.add(resources);
        return resources;
    }

    /** Lock for new sessions FIRST, then close every open session (idempotent). */
    public void retire() {
        retired = true;
        for (ProductiveResearchSessionResources resources : openSessions) {
            resources.close();
        }
        openSessions.clear();
    }
}
