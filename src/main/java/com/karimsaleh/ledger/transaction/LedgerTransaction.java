package com.karimsaleh.ledger.transaction;

import com.karimsaleh.ledger.account.Account;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.PERSIST)
    private List<Entry> entries = new ArrayList<>();

    protected LedgerTransaction() {
    }

    public LedgerTransaction(String idempotencyKey, String description) {
        this.idempotencyKey = idempotencyKey;
        this.description = description;
        this.createdAt = OffsetDateTime.now();
    }

    public void addEntry(Account account, long amountCents) {
        if (amountCents == 0) {
            throw new IllegalArgumentException("Entry amount cannot be zero");
        }
        entries.add(new Entry(this, account, amountCents));
    }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getDescription() { return description; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public List<Entry> getEntries() { return Collections.unmodifiableList(entries); }
}