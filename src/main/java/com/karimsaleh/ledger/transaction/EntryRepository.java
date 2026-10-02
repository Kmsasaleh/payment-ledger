package com.karimsaleh.ledger.transaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface EntryRepository extends JpaRepository<Entry, Long> {

    @Query("SELECT COALESCE(SUM(e.amountCents), 0) FROM Entry e WHERE e.account.id = :accountId")
    long balanceOf(@Param("accountId") UUID accountId);
}