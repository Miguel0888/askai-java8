package com.aresstack.askai.research.agent.narration;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The validator is the invariant every prompt iteration is tested against: facts verbatim, bounded length,
 * a pending decision ends as a question, no internal identifiers, no deletion promises.
 */
public class NarrationValidatorTest {

    private final NarrationValidator validator = new NarrationValidator();

    private NarrationPayload payload(String expectedDecision, int maxSentences, String... dataValues) {
        Map<String, String> data = new LinkedHashMap<String, String>();
        int i = 0;
        for (String value : dataValues) {
            data.put("k" + (i++), value);
        }
        return new NarrationPayload("situation", Arrays.asList("fact"), data,
                expectedDecision, maxSentences, null);
    }

    @Test
    public void aFaithfulNarrationPasses() {
        NarrationValidator.Result result = validator.validate(
                "I recorded 7 sources from 4 websites. Shall we review them together?",
                payload("review or continue", 4, "7", "4"));
        assertTrue(result.getViolations().toString(), result.isValid());
    }

    @Test
    public void aMissingNumberFails() {
        assertFalse(validator.validate(
                "I found many great sources! Shall we look at them?",
                payload("review", 4, "7")).isValid());
    }

    @Test
    public void tooManySentencesFail() {
        assertFalse(validator.validate(
                "One. Two. Three. Four. Five. Shall we?",
                payload("go", 3)).isValid());
    }

    @Test
    public void aPendingDecisionMustEndWithAQuestion() {
        assertFalse(validator.validate(
                "The outline is ready. You can approve it now.",
                payload("approve the outline", 4)).isValid());
        assertTrue(validator.validate(
                "The outline is ready. Would you like to approve it?",
                payload("approve the outline", 4)).isValid());
    }

    @Test
    public void internalIdentifiersNeverLeak() {
        assertFalse("underscore state ids are internal", validator.validate(
                "We are now in waiting_approval. Ready?",
                payload("approve", 4)).isValid());
        assertFalse("ALL_CAPS command names are internal", validator.validate(
                "Next I run APPROVE_OUTLINE. Ready?",
                payload("approve", 4)).isValid());
    }

    @Test
    public void deletionPromisesAreBlocked() {
        assertFalse(validator.validate(
                "I will delete the old chapters. OK?",
                payload("confirm", 4)).isValid());
        assertFalse(validator.validate(
                "Ich lösche die alten Kapitel dann. Einverstanden?",
                payload("confirm", 4)).isValid());
    }

    @Test
    public void emptyNarrationFails() {
        assertFalse(validator.validate("   ", payload(null, 4)).isValid());
    }
}
