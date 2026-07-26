package com.aresstack.askai.research.backend;

/** Injectable time source for event timestamps (deterministic in tests). */
public interface ResearchClock {

    long now();

    /** Wall-clock default for production. */
    static ResearchClock system() {
        return new ResearchClock() {
            public long now() {
                return System.currentTimeMillis();
            }
        };
    }
}
