package com.aresstack.askai.research.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * The L1 invariant: language is SESSION-local, mutable context. Two parallel sessions hold independent
 * languages, a live switch applies to the next utterance of exactly that session, and nothing is static.
 */
public class SessionResearchLanguageTest {

    @Test
    public void twoSessionsHoldIndependentLanguages() {
        SessionResearchLanguage a = new SessionResearchLanguage(ResearchLanguage.GERMAN);
        SessionResearchLanguage b = new SessionResearchLanguage(ResearchLanguage.ENGLISH);
        ResearchPlaybook playbookA = new ResearchPlaybook(a);
        ResearchPlaybook playbookB = new ResearchPlaybook(b);

        assertEquals("Webrecherche läuft", playbookA.progressTitle());
        assertEquals("Web research in progress", playbookB.progressTitle());

        a.change(ResearchLanguage.ENGLISH);
        assertEquals("A switched live", "Web research in progress", playbookA.progressTitle());
        assertEquals("B is untouched by A's switch", "Web research in progress", playbookB.progressTitle());

        b.change(ResearchLanguage.GERMAN);
        assertEquals("B switched live, A stays", "Web research in progress", playbookA.progressTitle());
        assertEquals("Webrecherche läuft", playbookB.progressTitle());
    }

    @Test
    public void theSwitchAppliesToTheNextUtteranceOfEveryConsumer() {
        SessionResearchLanguage language = new SessionResearchLanguage(ResearchLanguage.GERMAN);
        ResearchPlaybook playbook = new ResearchPlaybook(language);
        StaticNarrator narrator = new StaticNarrator(playbook);

        assertEquals("Zuletzt:", playbook.recentPagesTitle());
        language.change(ResearchLanguage.ENGLISH);
        assertEquals("the playbook reads the live value", "Recently:", playbook.recentPagesTitle());
        assertEquals("the narrator reads the live value too",
                "The collected evidence is waiting for your review.",
                narrator.describePhase("evidence", "waiting", true));
    }

    @Test
    public void codesNormalizeToTheEnglishDefault() {
        assertSame(ResearchLanguage.GERMAN, ResearchLanguage.fromCode("de"));
        assertSame(ResearchLanguage.GERMAN, ResearchLanguage.fromCode("DE"));
        assertSame(ResearchLanguage.ENGLISH, ResearchLanguage.fromCode("en"));
        assertSame(ResearchLanguage.ENGLISH, ResearchLanguage.fromCode(null));
        assertSame(ResearchLanguage.ENGLISH, ResearchLanguage.fromCode("fr"));

        SessionResearchLanguage language = new SessionResearchLanguage(null);
        assertSame(ResearchLanguage.ENGLISH, language.currentLanguage());
        language.change(null);
        assertSame(ResearchLanguage.ENGLISH, language.currentLanguage());
    }
}
