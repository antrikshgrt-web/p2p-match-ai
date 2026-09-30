package com.p2pmatchai.engine;

import com.p2pmatchai.model.Models.Finding;
import com.p2pmatchai.model.Models.Invoice;

import java.util.List;

/**
 * Generative layer (Annexure C): turns the engine's structured findings into a
 * plain-language explanation and recommended action for the AP executive.
 *
 * Two modes:
 *  - TEMPLATE (default): deterministic, offline, zero-cost, zero hallucination risk.
 *  - LLM: calls the Anthropic Messages API when the ANTHROPIC_API_KEY environment
 *    variable is set. The prompt is strictly grounded — the model may only rephrase
 *    findings the engine produced, and it can never change the classification.
 *
 * Security (Section 12): the key is read from the environment only. It is never
 * written to a file, logged, or returned to the browser.
 */
public final class NarrativeService {

    private final String apiKey;

    public NarrativeService() {
        this(true);
    }

    /**
     * @param enableLlm pass false to force deterministic offline narratives (used by the
     *                  evaluation harness so test runs are reproducible and free).
     */
    public NarrativeService(boolean enableLlm) {
        String key = enableLlm ? System.getenv("ANTHROPIC_API_KEY") : null;
        this.apiKey = (key == null || key.isBlank()) ? null : key;
    }

    public boolean llmEnabled() {
        return apiKey != null;
    }

    public String explain(Invoice inv, String classification, String recommendation,
                          List<Finding> findings) {
        String grounded = template(inv, classification, recommendation, findings);
        if (apiKey == null) {
            return grounded;
        }
        try {
            String polished = LlmClient.rephrase(apiKey, buildPrompt(grounded));
            return (polished == null || polished.isBlank()) ? grounded : polished;
        } catch (Exception e) {
            // Fail safe: never block a verification because the LLM is unavailable.
            return grounded + " (Note: narrative generated offline; LLM call failed.)";
        }
    }

    /* ------------------------------------------------------------------ */

    private String template(Invoice inv, String classification, String recommendation,
                            List<Finding> findings) {
        StringBuilder sb = new StringBuilder();

        if (classification.equals("CLEAN_MATCH")) {
            sb.append("Invoice ").append(inv.invoiceNumber())
                    .append(" matches the purchase order and goods receipt on quantity, unit price ")
                    .append("and tax details within the declared tolerances. ")
                    .append("Recommended action: confirm and park in SAP (MIR7/FBV2) after your check.");
            appendInfo(sb, findings);
            return sb.toString();
        }

        long blockers = findings.stream().filter(f -> f.severity().equals("BLOCKER")).count();
        sb.append("Invoice ").append(inv.invoiceNumber())
                .append(" could not be cleared automatically. ")
                .append(blockers == 1 ? "One issue was found" : blockers + " issues were found")
                .append(" during the three-way match:");

        int n = 0;
        for (Finding f : findings) {
            if (f.severity().equals("INFO")) {
                continue;
            }
            n++;
            sb.append(" (").append(n).append(") ").append(f.message());
        }

        sb.append(" Recommended action: ");
        switch (recommendation) {
            case "ESCALATE" -> sb.append("escalate to the AP team lead or procurement before any "
                    + "posting is attempted.");
            case "HOLD" -> sb.append("hold the invoice and verify the flagged fields against the "
                    + "physical document and SAP before parking.");
            default -> sb.append("review before parking.");
        }
        sb.append(" This is a recommendation only — the confirm, hold or escalate decision remains "
                + "with the AP executive.");
        appendInfo(sb, findings);
        return sb.toString();
    }

    private void appendInfo(StringBuilder sb, List<Finding> findings) {
        for (Finding f : findings) {
            if (f.severity().equals("INFO")) {
                sb.append(" Note: ").append(f.message());
            }
        }
    }

    private String buildPrompt(String grounded) {
        return """
               You are an accounts-payable assistant. Rewrite the verification note below \
               so an AP executive can read it quickly. Rules you must follow:
               1. Use only the facts in the note. Do not add numbers, causes or advice.
               2. Do not change the classification or the recommended action.
               3. Reply with the rewritten note only, in at most 90 words, plain text.

               NOTE:
               """ + grounded;
    }
}
