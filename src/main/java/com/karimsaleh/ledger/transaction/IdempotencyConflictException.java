package com.karimsaleh.ledger.transaction;

/** The same Idempotency-Key was reused with a different request body. Maps to HTTP 409. */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
