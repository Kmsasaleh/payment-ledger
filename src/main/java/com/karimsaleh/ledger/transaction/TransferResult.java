package com.karimsaleh.ledger.transaction;

import com.karimsaleh.ledger.fraud.TriageOutcome;

import java.util.UUID;

/** @param transaction null unless the transfer was posted (APPROVED or SKIPPED) */
public record TransferResult(TriageOutcome outcome, UUID decisionId, LedgerTransaction transaction) {
}
