package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.knowledge.live.LiveTopicProjection;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * #42 pins for the host-narrated topics answer: excluded topics never surface positively,
 * duplicates and blanks drop, the answer is suggestions-only wording (the user decides) and
 * a stale snapshot adds exactly one honest note.
 */
public class ResearchTopicsAnswerTest {

    private static LiveTopicProjection topic(String title) {
        return new LiveTopicProjection(Collections.singletonList("p-" + title),
                Collections.singletonList("p-" + title), title, 0.8d);
    }

    @Test
    public void blacklistedTopicsNeverSurfaceAndDuplicatesDrop() {
        List<String> titles = ResearchTopicsAnswer.presentableTitles(Arrays.asList(
                        topic("Exoskelette"), topic("GUI"), topic("Fatigue Monitoring"),
                        topic("gui"), topic("Exoskelette"), topic(" ")),
                Arrays.asList("GUI"));
        assertEquals(Arrays.asList("Exoskelette", "Fatigue Monitoring"), titles);
    }

    @Test
    public void theAnswerIsSuggestionsOnlyAndNamesTheUsersAuthority() {
        String answer = ResearchTopicsAnswer.render(
                Arrays.asList("Exoskelette", "Datenschutz"), false, false);
        assertTrue(answer.contains("- Exoskelette"));
        assertTrue(answer.contains("- Datenschutz"));
        assertTrue("nothing was added — stated explicitly",
                answer.contains("nothing was added to your concept"));
        assertTrue("the user decides", answer.contains("Tell me which ones to add"));
        assertFalse("no stale note without staleness", answer.contains("refreshed"));

        String stale = ResearchTopicsAnswer.render(
                Collections.singletonList("X"), true, false);
        assertTrue(stale.contains("being refreshed in the background"));
    }

    @Test
    public void anEmptyPresentableListStaysHonest() {
        String answer = ResearchTopicsAnswer.render(
                Collections.<String>emptyList(), false, true);
        assertTrue(answer.contains("keine zusätzlichen"));
    }
}
