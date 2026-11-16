package com.karimsaleh.ledger.fraud;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TransferDecisionRepository extends JpaRepository<TransferDecision, UUID> {

    Optional<TransferDecision> findByIdempotencyKey(String key);
}
