package com.karimsaleh.ledger.fraud;

import com.karimsaleh.ledger.fraud.SyntheticTransfers.Fixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FraudTriagePolicyTest {

    private final FraudTriagePolicy policy = new FraudTriagePolicy(props(0.80, 0.5));

    static Stream<Fixture> fixtures() {
        return SyntheticTransfers.all().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void fixtureProducesExpectedDecision(Fixture fixture) {
        TriageDecision decision = policy.decide(fixture.assessment());

        assertThat(decision.outcome()).isEqualTo(fixture.expectedOutcome());
        assertThat(decision.reasonCode()).isEqualTo(fixture.expectedReasonCode());
        assertThat(decision.reasons()).containsExactlyElementsOf(fixture.expectedReasons());
        assertThat(decision.assessment()).isSameAs(fixture.assessment());
    }

    // ---- confidence boundary ----

    @Test
    void confidenceExactlyAtMinimumIsNotHeld() {
        TriageDecision decision = policy.decide(assessment(RiskLevel.LOW, 0.80));

        assertThat(decision.outcome()).isEqualTo(TriageOutcome.APPROVED);
        assertThat(decision.reasonCode()).isEqualTo("LOW_RISK");
    }

    @Test
    void confidenceExactlyAtMinimumIsJudgedByRiskLevel() {
        // At 0.80 the risk level decides: MEDIUM is held for MEDIUM_RISK, not LOW_CONFIDENCE.
        TriageDecision decision = policy.decide(assessment(RiskLevel.MEDIUM, 0.80));

        assertThat(decision.outcome()).isEqualTo(TriageOutcome.HELD);
        assertThat(decision.reasonCode()).isEqualTo("MEDIUM_RISK");
    }

    @Test
    void confidenceJustBelowMinimumIsHeld() {
        TriageDecision decision = policy.decide(assessment(RiskLevel.LOW, 0.7999));

        assertThat(decision.outcome()).isEqualTo(TriageOutcome.HELD);
        assertThat(decision.reasonCode()).isEqualTo("LOW_CONFIDENCE");
    }

    @Test
    void highRiskJustBelowMinimumConfidenceIsHeldNotBlocked() {
        TriageDecision decision = policy.decide(assessment(RiskLevel.HIGH, 0.7999));

        assertThat(decision.outcome()).isEqualTo(TriageOutcome.HELD);
        assertThat(decision.reasonCode()).isEqualTo("LOW_CONFIDENCE");
        assertThat(decision.reasons()).isEmpty();
    }

    // ---- flag threshold boundary ----

    @Test
    void flagExactlyAtThresholdIsIncluded() {
        JevAssessment assessment = new JevAssessment(RiskLevel.HIGH, 0.90, Map.of(
                ReasonFlag.UNUSUAL_AMOUNT, 0.5,
                ReasonFlag.NEW_RECIPIENT, 0.1,
                ReasonFlag.VELOCITY_SPIKE, 0.1,
                ReasonFlag.ODD_TIME, 0.1), "fake-jev", 0, 0);

        TriageDecision decision = policy.decide(assessment);

        assertThat(decision.outcome()).isEqualTo(TriageOutcome.BLOCKED);
        assertThat(decision.reasons()).containsExactly(ReasonFlag.UNUSUAL_AMOUNT);
    }

    @Test
    void flagJustBelowThresholdIsExcluded() {
        JevAssessment assessment = new JevAssessment(RiskLevel.HIGH, 0.90, Map.of(
                ReasonFlag.UNUSUAL_AMOUNT, 0.4999,
                ReasonFlag.NEW_RECIPIENT, 0.5,
                ReasonFlag.VELOCITY_SPIKE, 0.1,
                ReasonFlag.ODD_TIME, 0.1), "fake-jev", 0, 0);

        TriageDecision decision = policy.decide(assessment);

        assertThat(decision.reasons()).containsExactly(ReasonFlag.NEW_RECIPIENT);
    }

    @Test
    void reasonsFollowEnumOrderNotProbabilityOrder() {
        JevAssessment assessment = new JevAssessment(RiskLevel.HIGH, 0.90, Map.of(
                ReasonFlag.ODD_TIME, 0.99,
                ReasonFlag.VELOCITY_SPIKE, 0.80,
                ReasonFlag.NEW_RECIPIENT, 0.60,
                ReasonFlag.UNUSUAL_AMOUNT, 0.51), "fake-jev", 0, 0);

        assertThat(policy.decide(assessment).reasons()).containsExactly(
                ReasonFlag.UNUSUAL_AMOUNT, ReasonFlag.NEW_RECIPIENT,
                ReasonFlag.VELOCITY_SPIKE, ReasonFlag.ODD_TIME);
    }

    @Test
    void reasonsAreOnlyReportedForBlocks() {
        JevAssessment assessment = new JevAssessment(RiskLevel.MEDIUM, 0.90, Map.of(
                ReasonFlag.UNUSUAL_AMOUNT, 0.99,
                ReasonFlag.NEW_RECIPIENT, 0.99,
                ReasonFlag.VELOCITY_SPIKE, 0.99,
                ReasonFlag.ODD_TIME, 0.99), "fake-jev", 0, 0);

        assertThat(policy.decide(assessment).reasons()).isEmpty();
    }

    // ---- failure path ----

    @Test
    void jevFailureIsHeldWithNoAssessment() {
        TriageDecision decision = policy.onFailure(new JevUnavailableException("timeout"));

        assertThat(decision.outcome()).isEqualTo(TriageOutcome.HELD);
        assertThat(decision.reasonCode()).isEqualTo("JEV_UNAVAILABLE");
        assertThat(decision.assessment()).isNull();
        assertThat(decision.reasons()).isEmpty();
    }

    // ---- thresholds come from config ----

    @Test
    void minimumConfidenceComesFromProperties() {
        JevAssessment lowAt90 = assessment(RiskLevel.LOW, 0.90);

        TriageDecision strict = new FraudTriagePolicy(props(0.95, 0.5)).decide(lowAt90);
        TriageDecision defaults = policy.decide(lowAt90);

        assertThat(strict.outcome()).isEqualTo(TriageOutcome.HELD);
        assertThat(strict.reasonCode()).isEqualTo("LOW_CONFIDENCE");
        assertThat(defaults.outcome()).isEqualTo(TriageOutcome.APPROVED);
    }

    @Test
    void flagThresholdComesFromProperties() {
        FraudTriagePolicy strict = new FraudTriagePolicy(props(0.80, 0.9));

        TriageDecision decision = strict.decide(SyntheticTransfers.ACCOUNT_TAKEOVER.assessment());

        // flags are 0.97 / 0.94 / 0.05 / 0.80, so only the first two reach 0.9
        assertThat(decision.reasons()).containsExactly(ReasonFlag.UNUSUAL_AMOUNT, ReasonFlag.NEW_RECIPIENT);
    }

    // ---- helpers ----

    private static FraudTriageProperties props(double minConfidence, double flagThreshold) {
        return new FraudTriageProperties(
                new FraudTriageProperties.Triage(minConfidence, flagThreshold, true),
                new FraudTriageProperties.Jev("https://test.invalid/typesafe", "typesafe-ai/jev", "test-key",
                        Duration.ofSeconds(1), Duration.ofSeconds(2), 500));
    }

    private static JevAssessment assessment(RiskLevel level, double confidence) {
        return new JevAssessment(level, confidence, Map.of(
                ReasonFlag.UNUSUAL_AMOUNT, 0.05,
                ReasonFlag.NEW_RECIPIENT, 0.05,
                ReasonFlag.VELOCITY_SPIKE, 0.05,
                ReasonFlag.ODD_TIME, 0.05), "fake-jev", 0, 0);
    }
}
