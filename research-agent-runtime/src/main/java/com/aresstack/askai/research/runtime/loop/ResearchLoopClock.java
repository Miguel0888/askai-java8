package com.aresstack.askai.research.runtime.loop;

/** Injectable time source — tests advance it manually; the loop never sleeps or waits on real time. */
public interface ResearchLoopClock {
    long currentTimeMillis();
}
