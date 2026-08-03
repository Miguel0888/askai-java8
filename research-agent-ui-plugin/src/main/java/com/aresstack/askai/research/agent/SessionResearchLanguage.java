package com.aresstack.askai.research.agent;

/**
 * The mutable session-owned language state: one instance per {@link ResearchAgentSession}, never shared
 * across sessions and never static — two parallel research tabs switch independently. Reads may come from
 * background callbacks, hence volatile; a {@code null} change is normalized to the English default.
 */
public final class SessionResearchLanguage implements ResearchLanguageProvider {

    private volatile ResearchLanguage language;

    public SessionResearchLanguage(ResearchLanguage initial) {
        this.language = initial == null ? ResearchLanguage.ENGLISH : initial;
    }

    public void change(ResearchLanguage value) {
        this.language = value == null ? ResearchLanguage.ENGLISH : value;
    }

    @Override
    public ResearchLanguage currentLanguage() {
        return language;
    }
}
