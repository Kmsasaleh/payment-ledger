package com.karimsaleh.ledger.account;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Version
    private long version;

    protected Account() {
        // Required by JPA
    }

    public Account(String name, AccountType type, String currency) {
        this.name = name;
        this.type = type;
        this.currency = currency;
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public AccountType getType() { return type; }
    public String getCurrency() { return currency; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
}