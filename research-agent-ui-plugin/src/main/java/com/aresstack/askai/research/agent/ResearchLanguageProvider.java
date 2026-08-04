package com.aresstack.askai.research.agent;

/**
 * Supplies the CURRENT research language of one session. Consumers (playbook, narrators) read it lazily on
 * every utterance, so a live language switch takes effect on the next generated text — never retroactively.
 */
public interface ResearchLanguageProvider {

    ResearchLanguage currentLanguage();
}
