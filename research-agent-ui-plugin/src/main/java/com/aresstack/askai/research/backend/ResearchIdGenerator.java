package com.aresstack.askai.research.backend;

import java.util.UUID;

/** Injectable id source for events/approvals (deterministic in tests). */
public interface ResearchIdGenerator {

    String newId();

    static ResearchIdGenerator random() {
        return new ResearchIdGenerator() {
            public String newId() {
                return UUID.randomUUID().toString();
            }
        };
    }
}
