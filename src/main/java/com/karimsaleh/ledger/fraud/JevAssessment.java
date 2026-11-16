package com.karimsaleh.ledger.fraud;

import java.util.Map;
import java.util.Objects;

/**
 * Jev's judgment about one transfer. Data only, no decision.
 *
 * @param riskLevel    Jev's chosen option for {@code fraud_risk}
 * @param confidence   Jev's confidence for that choice, 0.0-1.0
 * @param flags        probability (0.0-1.0) that each reason flag is true
 * @param model        model string echoed in the response, for the audit log
 * @param latencyMs    measured round-trip time of the call
 * @param inputTokens  billed input tokens from the response's usage field; -1 if missing
 */
public record JevAssessment(
        RiskLevel riskLevel,
        double confidence,
        Map<ReasonFlag, Double> flags,
        String model,
        long latencyMs,
        int inputTokens
) {
    public JevAssessment {
        Objects.requireNonNull(riskLevel, "riskLevel");
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1: " + confidence);
        }
        flags = Map.copyOf(Objects.requireNonNull(flags, "flags"));
    }
}
