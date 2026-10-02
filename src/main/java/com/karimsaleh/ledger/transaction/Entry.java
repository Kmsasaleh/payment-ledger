package com.karimsaleh.ledger.transaction;

import com.karimsaleh.ledger.account.Account;
import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "entries")
public class Entry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private LedgerTransaction transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Entry() {
        // Required by JPA
    }

    Entry(LedgerTransaction transaction, Account account, long amountCents) {
        this.transaction = transaction;
        this.account = account;
        this.amountCents = amountCents;
        this.createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public LedgerTransaction getTransaction() { return transaction; }
    public Account getAccount() { return account; }
    public long getAmountCents() { return amountCents; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}