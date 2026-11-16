package com.karimsaleh.ledger.fraud;

/**
 * Asks Jev for a fraud judgment about one transfer.
 *
 * <p>Jev only gives a judgment. The final approve / block / hold decision is made by
 * {@code FraudTriagePolicy}, never by an implementation of this interface.
 *
 * <p>Implementations: {@code HttpJevClient} (real calls, workstream A) and
 * {@code FakeJevClient} (tests, workstream B). Tests must never call the real API.
 */
public interface JevClient {

    /**
     * Makes at most one request to Jev. Implementations must not retry.
     *
     * @throws JevUnavailableException on any failure: timeout, HTTP error, budget or
     *         daily cap reached, or a response that can't be parsed. The caller then
     *         HOLDS the transfer; it is never auto-approved.
     */
    JevAssessment assess(TransferFeatures features) throws JevUnavailableException;
}
