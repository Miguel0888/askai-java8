package com.aresstack.askai.research.jsontree;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The safety boundary for model-generated JSON: strict rejection of every JavaScript-ism, with
 * diagnostics precise enough to hand straight back to the model — code, position, path where
 * reliably available, and a repair hint. Never a bare "Invalid JSON", never silent repair.
 */
public class StrictJsonParserDiagnosticsTest {

    private static JsonTreeDiagnostic reject(String json) {
        StrictJsonParseResult result = StrictJsonParser.parse(json);
        assertFalse("input must be rejected: " + json, result.isOk());
        JsonTreeDiagnostic diagnostic = result.getDiagnostic();
        assertEquals(JsonTreeErrorCode.JSON_SYNTAX_ERROR, diagnostic.getCode());
        assertFalse("a diagnostic needs a real message", diagnostic.getMessage().trim().isEmpty());
        return diagnostic;
    }

    @Test
    public void missingClosingBraceReportsPositionAndHint() {
        JsonTreeDiagnostic d = reject("{\"a\": 1, \"b\": [1, 2]");
        assertTrue("line is known", d.getLine() > 0);
        assertNotNull("a repair hint is given", d.getHint());
    }

    @Test
    public void missingClosingBracketIsRejected() {
        JsonTreeDiagnostic d = reject("{\"a\": [1, 2}");
        assertTrue(d.getLine() > 0);
    }

    @Test
    public void missingCommaReportsLineColumnAndPath() {
        JsonTreeDiagnostic d = reject("{\n  \"a\": 1\n  \"b\": 2\n}");
        assertEquals(3, d.getLine());
        assertTrue(d.getColumn() > 0);
        assertNotNull("the path narrows the search", d.getPath());
    }

    @Test
    public void unquotedPropertyNamesAreNotToleratedInStrictMode() {
        JsonTreeDiagnostic d = reject("{a: 1}");
        assertTrue("the message must explain the strictness, not tell the user to use lenient "
                        + "Gson: " + d.getMessage(),
                d.getMessage().contains("strict"));
        assertFalse("no raw Gson advice leaks through", d.getMessage().contains("setLenient"));
    }

    @Test
    public void singleQuotedStringsAreNotTolerated() {
        reject("{'a': 'b'}");
    }

    @Test
    public void trailingGarbageAfterTheDocumentIsRejected() {
        JsonTreeDiagnostic d = reject("{\"a\": 1} trailing");
        assertTrue("names the actual problem: " + d.getMessage(),
                d.getMessage().toLowerCase().contains("trailing")
                        || d.getMessage().contains("strict"));
    }

    @Test
    public void aSecondTopLevelValueIsRejected() {
        reject("{\"a\": 1}{\"b\": 2}");
    }

    @Test
    public void invalidEscapeSequencesAreRejectedWithAHint() {
        JsonTreeDiagnostic d = reject("{\"a\": \"bad \\x escape\"}");
        assertNotNull(d.getHint());
    }

    @Test
    public void unterminatedStringsAreRejected() {
        reject("{\"a\": \"never ends}");
    }

    @Test
    public void emptyInputIsAnHonestDiagnosticNotACrash() {
        JsonTreeDiagnostic d = reject("   ");
        assertEquals("a JSON value", d.getExpected());
    }

    @Test
    public void theModelFeedbackBlockLeadsWithTheCode() {
        JsonTreeDiagnostic d = reject("{\n  \"a\": 1\n  \"b\": 2\n}");
        String feedback = d.describeForModel();
        assertTrue("code first", feedback.startsWith("JSON_SYNTAX_ERROR"));
        assertTrue("position included", feedback.contains("Line 3"));
    }

    @Test
    public void nanAndInfinityAreNotTolerated() {
        reject("{\"a\": NaN}");
    }

    @Test
    public void treeParserSurfacesTheSameDiagnosticUnchanged() {
        JsonTreeParseResult result = JsonTreeParser.parse("{broken");
        assertFalse(result.isOk());
        assertEquals(JsonTreeErrorCode.JSON_SYNTAX_ERROR, result.getDiagnostic().getCode());
    }
}
