package com.aresstack.askai.research.runtime.team;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the model context for ONE turn from the active phase's {@link PhaseAssistantProfile}: the phase's
 * own system prompt, the per-turn research-context system message, and the running history. This is the single
 * place where "active phase → its assistant context" is realized, and the seam where RA-P6 §10/§11 will later
 * filter to this phase's own chat plus the latest relevant artifacts (per the profile's
 * {@link PhaseContextPolicy}). For now history is passed through unchanged; the policy is carried, not enforced.
 */
public final class PhaseContextAssembler {

    /** Supplies the current date ({@code YYYY-MM-DD}) as runtime context — never inferred from model memory. */
    public interface CurrentDate {
        String today();
    }

    /**
     * Supplies the session's CURRENT working-language display name ("English"/"German") per turn — read at
     * assemble time, so a live {@code set_language} applies to the NEXT turn without touching history.
     */
    public interface CurrentLanguage {
        String displayName();
    }

    /** Supplies the rendered scope fence for the NEXT turn — read at assemble time, like the language. */
    public interface CurrentScope {
        String rendered();
    }

    private static final CurrentScope EMPTY_SCOPE = new CurrentScope() {
        public String rendered() {
            return "";
        }
    };

    private static final CurrentLanguage ENGLISH_DEFAULT = new CurrentLanguage() {
        public String displayName() {
            return "English";
        }
    };

    private final CurrentDate currentDate;
    private final CurrentLanguage currentLanguage;
    /** Supplies the host's AUTHORITATIVE scope projection per turn; "" when the host sent none. */
    private volatile CurrentScope currentScope = EMPTY_SCOPE;

    public PhaseContextAssembler() {
        this(systemDate(), ENGLISH_DEFAULT);
    }

    /** Inject a fixed date (tests / deterministic runs). */
    public PhaseContextAssembler(CurrentDate currentDate) {
        this(currentDate, ENGLISH_DEFAULT);
    }

    /** System clock + the session's live working language (the production wiring). */
    public PhaseContextAssembler(CurrentLanguage currentLanguage) {
        this(systemDate(), currentLanguage);
    }

    /** The scope projection source; without it a turn simply carries no scope context. */
    public PhaseContextAssembler withCurrentScope(CurrentScope scope) {
        this.currentScope = scope == null ? EMPTY_SCOPE : scope;
        return this;
    }

    public PhaseContextAssembler(CurrentDate currentDate, CurrentLanguage currentLanguage) {
        this.currentDate = currentDate;
        this.currentLanguage = currentLanguage == null ? ENGLISH_DEFAULT : currentLanguage;
    }

    /** The live working language's display name — the greeting bootstrap names it explicitly. */
    public String workingLanguageDisplayName() {
        return currentLanguage.displayName();
    }

    private static CurrentDate systemDate() {
        return new CurrentDate() {
            public String today() {
                return java.time.LocalDate.now().toString();
            }
        };
    }

    /**
     * system(phase prompt) + system(current date) + system(current working language) + system(live research
     * context) + the running history. The
     * profile decides the FIRST system message, so a different active phase yields a different assistant
     * context. The current date is supplied from the host clock so the model never invents a year in, e.g.,
     * search queries.
     */
    public List<ChatMessage> assemble(PhaseAssistantProfile profile, TeamAgentStateView state,
                                      String confirmedQuestion, List<String> confirmedAspects,
                                      String proposedQuestion, List<String> proposedAspects,
                                      List<ChatMessage> history) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(profile.getSystemPrompt()));
        messages.add(ChatMessage.system("Current date: " + currentDate.today()));
        messages.add(ChatMessage.system(
                TeamAgentPlaybook.workingLanguageContext(currentLanguage.displayName())));
        messages.add(ChatMessage.system(TeamAgentPlaybook.stateContext(
                state, confirmedQuestion, confirmedAspects, proposedQuestion, proposedAspects)));
        // The scope the HOST holds — authoritative for this turn. Without it the model would rebuild the
        // scope from the conversation, which is how earlier decisions quietly disappear.
        String scope = currentScope.rendered();
        if (!scope.isEmpty()) {
            messages.add(ChatMessage.system(TeamAgentPlaybook.scopeFenceContext(scope)));
        }
        if (history != null) {
            messages.addAll(history);
        }
        return messages;
    }
}
