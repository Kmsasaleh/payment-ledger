package com.karimsaleh.ledger.fraud;

import com.karimsaleh.ledger.account.Account;
import com.karimsaleh.ledger.transaction.EntryRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

/** Computes the facts Jev sees about a transfer. Read-only queries, no locks. */
@Component
public class TransferFeatureBuilder {

    private final EntryRepository entryRepository;
    private final Clock clock;

    public TransferFeatureBuilder(EntryRepository entryRepository, Clock clock) {
        this.entryRepository = entryRepository;
        this.clock = clock;
    }

    public TransferFeatures build(Account from, Account to, long amountCents) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return new TransferFeatures(
                amountCents,
                from.getCurrency(),
                Math.round(entryRepository.medianOutgoingLast20(from.getId())),
                entryRepository.countOutgoing(from.getId()),
                entryRepository.countTransfersBetween(from.getId(), to.getId()),
                entryRepository.countOutgoingSince(from.getId(), now.minusMinutes(10)),
                now.getHour(),
                Duration.between(from.getCreatedAt(), now).toDays());
    }
}
