package com.karimsaleh.ledger.fraud;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scriptable in-memory {@link JevClient} for tests. Never touches the network, so it costs $0.
 *
 * <p>Precedence on each call: a scripted failure ({@link #willThrow}) wins over a per-amount
 * response ({@link #willReturnFor}), which wins over the default response ({@link #willReturn}).
 * Every call counts toward {@link #callCount()}, including calls that throw.
 *
 * <p>Thread-safe: the concurrent transfer test calls it from several threads.
 */
public class FakeJevClient implements JevClient {

    private final AtomicInteger calls = new AtomicInteger();
    private final Map<Long, JevAssessment> byAmount = new ConcurrentHashMap<>();
    private volatile JevAssessment defaultResponse = defaultAssessment();
    private volatile JevUnavailableException failure;
    private volatile TransferFeatures lastFeatures;

    /** Confident LOW with every flag at 0.05. */
    public static JevAssessment defaultAssessment() {
        return new JevAssessment(
                RiskLevel.LOW,
                0.95,
                Map.of(ReasonFlag.UNUSUAL_AMOUNT, 0.05,
                        ReasonFlag.NEW_RECIPIENT, 0.05,
                        ReasonFlag.VELOCITY_SPIKE, 0.05,
                        ReasonFlag.ODD_TIME, 0.05),
                "fake-jev",
                0L,
                0);
    }

    @Override
    public JevAssessment assess(TransferFeatures features) throws JevUnavailableException {
        calls.incrementAndGet();
        lastFeatures = features;

        JevUnavailableException scriptedFailure = failure;
        if (scriptedFailure != null) {
            throw scriptedFailure;
        }
        JevAssessment scripted = byAmount.get(features.amountCents());
        return scripted != null ? scripted : defaultResponse;
    }

    /** Answer every call with this (unless a per-amount response or failure applies). Clears any scripted failure. */
    public FakeJevClient willReturn(JevAssessment assessment) {
        this.defaultResponse = assessment;
        this.failure = null;
        return this;
    }

    /** Fail every call with this exception. */
    public FakeJevClient willThrow(JevUnavailableException exception) {
        this.failure = exception;
        return this;
    }

    /** Answer calls for exactly this transfer amount with this assessment. */
    public FakeJevClient willReturnFor(long amountCents, JevAssessment assessment) {
        byAmount.put(amountCents, assessment);
        return this;
    }

    public int callCount() {
        return calls.get();
    }

    /** Features from the most recent call, or null if never called. */
    public TransferFeatures lastFeatures() {
        return lastFeatures;
    }

    /** Back to the default: confident LOW, no failure, no per-amount scripts, zero calls. */
    public void reset() {
        calls.set(0);
        byAmount.clear();
        defaultResponse = defaultAssessment();
        failure = null;
        lastFeatures = null;
    }
}
