package com.karimsaleh.ledger.transaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface EntryRepository extends JpaRepository<Entry, Long> {

    @Query("SELECT COALESCE(SUM(e.amountCents), 0) FROM Entry e WHERE e.account.id = :accountId")
    long balanceOf(@Param("accountId") UUID accountId);

    // --- Fraud features (read-only). Debits are negative entries; amounts come back as positive cents. ---

    /** Median of the account's last 20 outgoing amounts; 0 if none. */
    @Query(nativeQuery = true, value = """
            SELECT COALESCE(percentile_cont(0.5) WITHIN GROUP (ORDER BY t.amount), 0)::float8
            FROM (SELECT -amount_cents AS amount
                  FROM entries
                  WHERE account_id = :accountId AND amount_cents < 0
                  ORDER BY created_at DESC, id DESC
                  LIMIT 20) t
            """)
    double medianOutgoingLast20(@Param("accountId") UUID accountId);

    @Query(nativeQuery = true, value = """
            SELECT COUNT(*) FROM entries WHERE account_id = :accountId AND amount_cents < 0
            """)
    long countOutgoing(@Param("accountId") UUID accountId);

    /** Past transactions with a debit on {@code fromId} and a credit on {@code toId}. */
    @Query(nativeQuery = true, value = """
            SELECT COUNT(DISTINCT d.transaction_id)
            FROM entries d
            JOIN entries c ON c.transaction_id = d.transaction_id
            WHERE d.account_id = :fromId AND d.amount_cents < 0
              AND c.account_id = :toId AND c.amount_cents > 0
            """)
    long countTransfersBetween(@Param("fromId") UUID fromId, @Param("toId") UUID toId);

    @Query(nativeQuery = true, value = """
            SELECT COUNT(*) FROM entries
            WHERE account_id = :accountId AND amount_cents < 0 AND created_at > :since
            """)
    long countOutgoingSince(@Param("accountId") UUID accountId, @Param("since") OffsetDateTime since);
}
