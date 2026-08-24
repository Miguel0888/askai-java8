package com.aresstack.askai.research.visualize;

/**
 * Validates model-generated Mermaid BEFORE it reaches the UI, so a syntax error becomes concrete
 * feedback for the model's retry instead of a broken diagram in front of the user.
 */
public interface MermaidValidator {

    /** @return {@code null} when the source renders, else the renderer's error message. */
    String validate(String mermaid);

    /**
     * The production validator: the HOST-provided GraalJS Mermaid renderer. When the host library
     * is unavailable (tests, fake hosts) validation is skipped — never a crash, never a false
     * rejection.
     */
    final class Graal implements MermaidValidator {
        public String validate(String mermaid) {
            if (mermaid == null || mermaid.trim().isEmpty()) {
                return "empty diagram source";
            }
            try {
                com.aresstack.mermaid.JsExecutionResult result =
                        com.aresstack.Mermaid.renderDetailed(mermaid);
                if (result.isSuccessful()) {
                    return null;
                }
                String error = result.getErrorMessage();
                return error == null || error.trim().isEmpty() ? "mermaid failed to render" : error;
            } catch (RuntimeException failed) {
                return failed.getMessage() == null ? "mermaid failed to render" : failed.getMessage();
            } catch (LinkageError hostMissing) {
                return null; // no renderer available here — do not reject what we cannot check
            }
        }
    }
}
