package com.karimsaleh.ledger.transaction;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    @EntityGraph(attributePaths = {"entries", "entries.account"})
    Optional<LedgerTransaction> findByIdempotencyKey(String idempotencyKey);
}