package com.karimsaleh.ledger.fraud;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Turns Jev's judgment into our decision. Pure: no I/O.
 * There is exactly one code path that returns APPROVED (LOW risk with enough confidence).
 */
@Component
public class FraudTriagePolicy {

    private final FraudTriageProperties props;

    public FraudTriagePolicy(FraudTriageProperties props) {
        this.props = props;
    }

    public TriageDecision decide(JevAssessment a) {
        if (a.confidence() < props.triage().minConfidence()) {
            return new TriageDecision(TriageOutcome.HELD, "LOW_CONFIDENCE", List.of(), a);
        }
        return switch (a.riskLevel()) {
            case HIGH -> new TriageDecision(TriageOutcome.BLOCKED, "HIGH_RISK", flagsOver(a), a);
            case MEDIUM -> new TriageDecision(TriageOutcome.HELD, "MEDIUM_RISK", List.of(), a);
            case LOW -> new TriageDecision(TriageOutcome.APPROVED, "LOW_RISK", List.of(), a);
        };
    }

    public TriageDecision onFailure(JevUnavailableException e) {
        return new TriageDecision(TriageOutcome.HELD, "JEV_UNAVAILABLE", List.of(), null);
    }

    /** Flags at or above the threshold, in enum order. */
    private List<ReasonFlag> flagsOver(JevAssessment a) {
        return Arrays.stream(ReasonFlag.values())
                .filter(f -> a.flags().getOrDefault(f, 0.0) >= props.triage().flagThreshold())
                .toList();
    }
}
