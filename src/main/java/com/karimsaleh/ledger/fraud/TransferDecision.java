package com.karimsaleh.ledger.fraud;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Audit row: one per idempotency key, whatever the outcome. Only APPROVED/SKIPPED have a transaction. */
@Entity
@Table(name = "transfer_decisions")
public class TransferDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "from_account_id", nullable = false)
    private UUID fromAccountId;

    @Column(name = "to_account_id", nullable = false)
    private UUID toAccountId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TriageOutcome outcome;

    @Column(name = "reason_code", nullable = false)
    private String reasonCode;

    @Convert(converter = ReasonFlagListConverter.class)
    @Column(nullable = false)
    private List<ReasonFlag> reasons;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level")
    private RiskLevel riskLevel;

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "jev_model")
    private String jevModel;

    @Column(name = "jev_latency_ms")
    private Long jevLatencyMs;

    @Column(name = "jev_input_tokens")
    private Integer jevInputTokens;

    @Column(name = "transaction_id", unique = true)
    private UUID transactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected TransferDecision() {
    }

    public TransferDecision(String idempotencyKey, String requestHash, UUID fromAccountId, UUID toAccountId,
                            long amountCents, TriageDecision decision, UUID transactionId) {
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amountCents = amountCents;
        this.outcome = decision.outcome();
        this.reasonCode = decision.reasonCode();
        this.reasons = decision.reasons();
        JevAssessment a = decision.assessment();
        if (a != null) {
            this.riskLevel = a.riskLevel();
            this.confidence = BigDecimal.valueOf(a.confidence()).setScale(3, RoundingMode.HALF_UP);
            this.jevModel = a.model();
            this.jevLatencyMs = a.latencyMs();
            this.jevInputTokens = a.inputTokens();
        }
        this.transactionId = transactionId;
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public UUID getFromAccountId() { return fromAccountId; }
    public UUID getToAccountId() { return toAccountId; }
    public long getAmountCents() { return amountCents; }
    public TriageOutcome getOutcome() { return outcome; }
    public String getReasonCode() { return reasonCode; }
    public List<ReasonFlag> getReasons() { return reasons; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public BigDecimal getConfidence() { return confidence; }
    public String getJevModel() { return jevModel; }
    public Long getJevLatencyMs() { return jevLatencyMs; }
    public Integer getJevInputTokens() { return jevInputTokens; }
    public UUID getTransactionId() { return transactionId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
