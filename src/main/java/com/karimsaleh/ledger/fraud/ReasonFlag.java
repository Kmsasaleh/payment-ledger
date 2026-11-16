package com.karimsaleh.ledger.fraud;

/**
 * Reason flags, each asked as a separate Jev yes/no ("noul") question in the same request.
 * {@link #questionId()} is the key used in the request and response JSON.
 */
public enum ReasonFlag {
    UNUSUAL_AMOUNT("unusual_amount"),
    NEW_RECIPIENT("new_recipient"),
    VELOCITY_SPIKE("velocity_spike"),
    ODD_TIME("odd_time");

    private final String questionId;

    ReasonFlag(String questionId) {
        this.questionId = questionId;
    }

    public String questionId() {
        return questionId;
    }
}
