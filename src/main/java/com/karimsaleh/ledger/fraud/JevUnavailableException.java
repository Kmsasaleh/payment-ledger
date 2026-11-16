package com.karimsaleh.ledger.fraud;

/**
 * Jev could not give a usable answer. The transfer must be HELD, never approved.
 * Checked exception on purpose: the compiler forces callers to handle the failure path.
 */
public class JevUnavailableException extends Exception {

    public JevUnavailableException(String message) {
        super(message);
    }

    public JevUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
