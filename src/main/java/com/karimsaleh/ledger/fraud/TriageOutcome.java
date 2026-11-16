package com.karimsaleh.ledger.fraud;

/**
 * Final result of triage. Only APPROVED and SKIPPED transfers create ledger entries.
 * SKIPPED = triage not applied (e.g. a deposit from a SYSTEM account).
 */
public enum TriageOutcome {
    APPROVED, BLOCKED, HELD, SKIPPED;

    public boolean postsToLedger() {
        return this == APPROVED || this == SKIPPED;
    }
}
