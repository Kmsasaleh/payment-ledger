package com.karimsaleh.ledger.transaction;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record TransferResponse(
        UUID transactionId,
        String idempotencyKey,
        String description,
        OffsetDateTime createdAt,
        List<EntryLine> entries
) {
    public record EntryLine(UUID accountId, long amountCents) {
    }

    static TransferResponse from(LedgerTransaction transaction) {
        List<EntryLine> lines = transaction.getEntries().stream()
                .map(entry -> new EntryLine(entry.getAccount().getId(), entry.getAmountCents()))
                .toList();
        return new TransferResponse(
                transaction.getId(),
                transaction.getIdempotencyKey(),
                transaction.getDescription(),
                transaction.getCreatedAt(),
                lines);
    }
}