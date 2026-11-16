package com.karimsaleh.ledger.transaction;

import java.util.UUID;

/** Body for HELD and BLOCKED transfers. Deliberately has no reason flags. status: PENDING_REVIEW or BLOCKED. */
public record TransferDecisionResponse(UUID decisionId, String status) {
}
