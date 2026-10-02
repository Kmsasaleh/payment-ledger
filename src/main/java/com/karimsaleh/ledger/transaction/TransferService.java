package com.karimsaleh.ledger.transaction;

import com.karimsaleh.ledger.account.Account;
import com.karimsaleh.ledger.account.AccountRepository;
import com.karimsaleh.ledger.account.AccountType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class TransferService {

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final EntryRepository entryRepository;

    public TransferService(AccountRepository accountRepository,
                           LedgerTransactionRepository transactionRepository,
                           EntryRepository entryRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
    }

    @Transactional
    public LedgerTransaction transfer(String idempotencyKey,
                                      UUID fromAccountId,
                                      UUID toAccountId,
                                      long amountCents,
                                      String description) {

        // 1. Idempotency: if we've seen this key before, return the original result
        Optional<LedgerTransaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        // 2. Validate the request
        if (amountCents <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (fromAccountId.equals(toAccountId)) {
            throw new IllegalArgumentException("Cannot transfer to the same account");
        }

        // Lock both accounts, always in the same order (smaller ID first) to prevent deadlocks
        UUID firstId = fromAccountId.compareTo(toAccountId) < 0 ? fromAccountId : toAccountId;
        UUID secondId = firstId.equals(fromAccountId) ? toAccountId : fromAccountId;

        Account first = accountRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + firstId));
        Account second = accountRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + secondId));

        Account from = first.getId().equals(fromAccountId) ? first : second;
        Account to = (from == first) ? second : first;

        if (!from.getCurrency().equals(to.getCurrency())) {
            throw new IllegalArgumentException("Currency mismatch: " + from.getCurrency() + " vs " + to.getCurrency());
        }

        // 3. User accounts can't go negative; system accounts can
        if (from.getType() == AccountType.USER) {
            long balance = entryRepository.balanceOf(fromAccountId);
            if (balance < amountCents) {
                throw new InsufficientFundsException(
                        "Insufficient funds: balance " + balance + ", requested " + amountCents);
            }
        }

        // 4. Double entry: money leaves one account and enters the other
        LedgerTransaction transaction = new LedgerTransaction(idempotencyKey, description);
        transaction.addEntry(from, -amountCents);
        transaction.addEntry(to, amountCents);

        return transactionRepository.save(transaction);
    }
}