package com.karimsaleh.ledger.fraud;

import java.util.List;
import java.util.Objects;

/**
 * The decision our code makes, produced only by {@code FraudTriagePolicy}.
 *
 * @param outcome     what happens to the transfer
 * @param reasonCode  why, e.g. "LOW_RISK", "HIGH_RISK", "LOW_CONFIDENCE", "MEDIUM_RISK", "JEV_UNAVAILABLE"
 * @param reasons     reason flags that crossed the flag threshold (BLOCK); empty otherwise.
 *                    Stored and logged server-side, never returned to the API caller.
 * @param assessment  Jev's raw judgment for the audit row; null if Jev failed or wasn't called
 */
public record TriageDecision(
        TriageOutcome outcome,
        String reasonCode,
        List<ReasonFlag> reasons,
        JevAssessment assessment
) {
    public TriageDecision {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(reasonCode, "reasonCode");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
    }
}
