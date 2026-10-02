package com.karimsaleh.ledger.account;

import java.util.UUID;

public record AccountResponse(
        UUID id,
        String name,
        AccountType type,
        String currency,
        long balanceCents
) {
    static AccountResponse from(Account account, long balanceCents) {
        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getType(),
                account.getCurrency(),
                balanceCents);
    }
}