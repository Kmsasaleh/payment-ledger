package com.karimsaleh.ledger.fraud;

import java.util.List;
import java.util.Map;

/**
 * Fraud-like transfer fixtures. Each pairs the features our code would send to Jev with the
 * assessment a {@link FakeJevClient} should return, and what the policy must decide.
 *
 * <p>Every fixture uses a distinct amount so integration tests can script the fake per amount
 * with {@link FakeJevClient#willReturnFor(long, JevAssessment)}.
 */
public final class SyntheticTransfers {

    private SyntheticTransfers() {
    }

    public record Fixture(
            String name,
            TransferFeatures features,
            JevAssessment assessment,
            TriageOutcome expectedOutcome,
            String expectedReasonCode,
            List<ReasonFlag> expectedReasons
    ) {
        public long amountCents() {
            return features.amountCents();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** Ordinary payment: a regular payee at a normal amount and a normal hour. */
    public static final Fixture NORMAL = new Fixture(
            "NORMAL",
            new TransferFeatures(4_200, "CAD", 4_000, 30, 6, 0, 14, 400),
            assessment(RiskLevel.LOW, 0.95, 0.05, 0.05, 0.05, 0.05),
            TriageOutcome.APPROVED, "LOW_RISK", List.of());

    /**
     * Card testing: a stolen card number is probed with many tiny payments to a fresh
     * destination before a big one. Signature is a burst of small outgoing transfers.
     */
    public static final Fixture CARD_TESTING = new Fixture(
            "CARD_TESTING",
            new TransferFeatures(150, "CAD", 4_000, 12, 0, 8, 14, 200),
            assessment(RiskLevel.HIGH, 0.90, 0.30, 0.85, 0.92, 0.10),
            TriageOutcome.BLOCKED, "HIGH_RISK",
            List.of(ReasonFlag.NEW_RECIPIENT, ReasonFlag.VELOCITY_SPIKE));

    /**
     * Account takeover: a brand-new account (or a hijacked one) drains a large amount to a
     * never-seen recipient in the middle of the night. About 60x the median payment.
     */
    public static final Fixture ACCOUNT_TAKEOVER = new Fixture(
            "ACCOUNT_TAKEOVER",
            new TransferFeatures(250_000, "CAD", 4_167, 5, 0, 0, 3, 2),
            assessment(RiskLevel.HIGH, 0.92, 0.97, 0.94, 0.05, 0.80),
            TriageOutcome.BLOCKED, "HIGH_RISK",
            List.of(ReasonFlag.UNUSUAL_AMOUNT, ReasonFlag.NEW_RECIPIENT, ReasonFlag.ODD_TIME));

    /**
     * Possible first-party payment to an unfamiliar person (marketplace or rental scam
     * victim paying a stranger). Modest amount, normal hour, so Jev can't make up its mind.
     * Low confidence is checked first, so this is held for that reason, not for MEDIUM risk.
     */
    public static final Fixture AMBIGUOUS = new Fixture(
            "AMBIGUOUS",
            new TransferFeatures(40_000, "CAD", 4_000, 20, 0, 0, 13, 300),
            assessment(RiskLevel.MEDIUM, 0.55, 0.40, 0.60, 0.10, 0.10),
            TriageOutcome.HELD, "LOW_CONFIDENCE", List.of());

    /**
     * Mule-style pass-through: a larger-than-usual payment to a new payee that Jev is sure
     * is moderately risky. Not enough to block, enough for a human to look at.
     */
    public static final Fixture CONFIDENT_MEDIUM = new Fixture(
            "CONFIDENT_MEDIUM",
            new TransferFeatures(90_000, "CAD", 10_000, 25, 0, 1, 11, 120),
            assessment(RiskLevel.MEDIUM, 0.90, 0.70, 0.65, 0.10, 0.10),
            TriageOutcome.HELD, "MEDIUM_RISK", List.of());

    /**
     * Looks like account takeover, but Jev's confidence is only 0.70. We never block on a
     * shaky HIGH: a false block of a real customer is worse than a human review.
     */
    public static final Fixture HIGH_BUT_UNSURE = new Fixture(
            "HIGH_BUT_UNSURE",
            new TransferFeatures(120_000, "CAD", 5_000, 8, 0, 0, 4, 10),
            assessment(RiskLevel.HIGH, 0.70, 0.85, 0.80, 0.10, 0.60),
            TriageOutcome.HELD, "LOW_CONFIDENCE", List.of());

    public static List<Fixture> all() {
        return List.of(NORMAL, CARD_TESTING, ACCOUNT_TAKEOVER, AMBIGUOUS, CONFIDENT_MEDIUM, HIGH_BUT_UNSURE);
    }

    /** Flag probabilities are given in enum order: unusual_amount, new_recipient, velocity_spike, odd_time. */
    private static JevAssessment assessment(RiskLevel level, double confidence,
                                            double unusualAmount, double newRecipient,
                                            double velocitySpike, double oddTime) {
        return new JevAssessment(level, confidence,
                Map.of(ReasonFlag.UNUSUAL_AMOUNT, unusualAmount,
                        ReasonFlag.NEW_RECIPIENT, newRecipient,
                        ReasonFlag.VELOCITY_SPIKE, velocitySpike,
                        ReasonFlag.ODD_TIME, oddTime),
                "fake-jev", 0L, 0);
    }
}
