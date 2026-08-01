package com.aresstack.askai.research.runtime.team;

import com.aresstack.askai.research.acp.ResearchRunWire;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The scoping projection travels the existing research wire: the runtime encodes, the UI-side parser decodes,
 * and this round-trip pins the format (map + suggestions + advice), with the brief deliberately absent.
 */
public class ScopingProjectionEncoderTest {

    private static ScopingAssistantOutput scoping(String map, List<SearchSuggestion> suggestions,
                                                  PhaseAdvice advice) {
        return new ScopingAssistantOutput("msg", "# Brief\nWearables", map, suggestions, advice);
    }

    @Test
    public void aScopingOutputRoundTripsMapSuggestionsAndAdvice() {
        ScopingAssistantOutput output = scoping(
                "mindmap\n  root((Wearables))\n    Audio",
                Arrays.asList(
                        new SearchSuggestion("wearables audio video", "current tech", 1),
                        new SearchSuggestion("smart glasses privacy GDPR", "", 2),   // empty purpose
                        new SearchSuggestion("earables research", "audio", 3)),
                new PhaseAdvice(PhaseAdviceRecommendation.CONTINUE, "precise enough"));

        String line = ScopingProjectionEncoder.wireLineFor("scoping", output);
        Map<String, String> f = ResearchRunWire.fields(line);

        assertEquals("scoping", ResearchRunWire.decodedField(f, "phase"));
        assertEquals("mindmap\n  root((Wearables))\n    Audio", ResearchRunWire.decodedField(f, "map"));
        assertEquals("CONTINUE", f.get("advice"));
        assertEquals("precise enough", ResearchRunWire.decodedField(f, "advicereason"));

        List<String[]> suggestions = ResearchRunWire.decodedSuggestions(f);
        assertEquals(3, suggestions.size());
        assertEquals("wearables audio video", suggestions.get(0)[0]);
        assertEquals("current tech", suggestions.get(0)[1]);
        assertEquals("1", suggestions.get(0)[2]);
        assertEquals("an empty purpose stays aligned", "", suggestions.get(1)[1]);
        assertEquals("smart glasses privacy GDPR", suggestions.get(1)[0]);
        assertEquals("earables research", suggestions.get(2)[0]);
        assertEquals("3", suggestions.get(2)[2]);
    }

    @Test
    public void aNonScopingOutputProjectsNothing() {
        assertNull(ScopingProjectionEncoder.wireLineFor("outline", TeamAgentTurn.message("hi")));
    }

    @Test
    public void anEmptyMapAndNoSuggestionsStillProduceAValidProjection() {
        String line = ScopingProjectionEncoder.wireLineFor("scoping",
                scoping("", Collections.<SearchSuggestion>emptyList(), PhaseAdvice.neutral()));
        Map<String, String> f = ResearchRunWire.fields(line);

        assertTrue("still a scopeassist line", ResearchRunWire.TYPE_SCOPEASSIST.equals(
                ResearchRunWire.typeOf(line)));
        assertEquals("", ResearchRunWire.decodedField(f, "map"));
        assertTrue(ResearchRunWire.decodedSuggestions(f).isEmpty());
        assertEquals("NEUTRAL", f.get("advice"));
    }
}
