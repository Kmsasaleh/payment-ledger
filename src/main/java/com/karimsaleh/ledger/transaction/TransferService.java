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

        Optional<LedgerTransaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        if (amountCents <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (fromAccountId.equals(toAccountId)) {
            throw new IllegalArgumentException("Cannot transfer to the same account");
        }

        // Lock both accounts in a consistent order (smaller ID first) to prevent deadlocks
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

        if (from.getType() == AccountType.USER) {
            long balance = entryRepository.balanceOf(fromAccountId);
            if (balance < amountCents) {
                throw new InsufficientFundsException(
                        "Insufficient funds: balance " + balance + ", requested " + amountCents);
            }
        }

        LedgerTransaction transaction = new LedgerTransaction(idempotencyKey, description);
        transaction.addEntry(from, -amountCents);
        transaction.addEntry(to, amountCents);

        return transactionRepository.save(transaction);
    }
}