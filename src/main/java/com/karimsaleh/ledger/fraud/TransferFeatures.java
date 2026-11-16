package com.karimsaleh.ledger.fraud;

/**
 * Facts our code computes from the ledger before asking Jev. Jev can't see the
 * database, so this record is everything it knows about the transfer.
 * No account IDs or names are sent: Jev doesn't need them.
 *
 * @param amountCents                       transfer amount in integer cents
 * @param currency                          ISO 4217 code, e.g. "CAD"
 * @param senderMedianOutgoingCentsLast20   median of the sender's last 20 outgoing amounts; 0 if none
 * @param senderOutgoingCountTotal          how many outgoing transfers the sender has ever made
 * @param senderPriorTransfersToRecipient   past transfers from this sender to this recipient
 * @param senderOutgoingCountLast10Min      sender's outgoing transfers in the last 10 minutes
 * @param hourUtc                           0-23, server time in UTC (accounts have no timezone)
 * @param senderAccountAgeDays              days since the sender account was created
 */
public record TransferFeatures(
        long amountCents,
        String currency,
        long senderMedianOutgoingCentsLast20,
        long senderOutgoingCountTotal,
        long senderPriorTransfersToRecipient,
        long senderOutgoingCountLast10Min,
        int hourUtc,
        long senderAccountAgeDays
) {
}
