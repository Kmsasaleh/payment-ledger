package com.karimsaleh.ledger.transaction;

import com.karimsaleh.ledger.account.Account;
import com.karimsaleh.ledger.account.AccountRepository;
import com.karimsaleh.ledger.account.AccountType;
import com.karimsaleh.ledger.fraud.TransferDecision;
import com.karimsaleh.ledger.fraud.TransferDecisionRepository;
import com.karimsaleh.ledger.fraud.TriageDecision;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The only place that writes ledger rows. Kept as its own bean on purpose.
 *
 * <p>Spring concept: {@code @Transactional} works through a proxy that wraps the bean. If
 * {@code TransferService} called a {@code @Transactional} method on {@code this}, the call would
 * skip the proxy and no transaction would start (the "self-invocation trap"). Calling it on
 * another bean goes through the proxy, so the transaction really begins and commits.
 */
@Service
public class LedgerPostingService {

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final EntryRepository entryRepository;
    private final TransferDecisionRepository decisionRepository;

    public LedgerPostingService(AccountRepository accountRepository,
                                LedgerTransactionRepository transactionRepository,
                                EntryRepository entryRepository,
                                TransferDecisionRepository decisionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.decisionRepository = decisionRepository;
    }

    /** Posts the transaction and its decision row in ONE database transaction. */
    @Transactional
    public TransferResult post(String idempotencyKey, String requestHash,
                               UUID fromAccountId, UUID toAccountId,
                               long amountCents, String description, TriageDecision decision) {

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

        // Re-check under the lock: the unlocked pre-check in TransferService may be stale by now.
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
        transaction = transactionRepository.save(transaction);

        TransferDecision row = decisionRepository.save(new TransferDecision(
                idempotencyKey, requestHash, fromAccountId, toAccountId, amountCents, decision, transaction.getId()));
        return new TransferResult(decision.outcome(), row.getId(), transaction);
    }

    /** HELD / BLOCKED: records the decision only. No transaction, no entries, no locks. */
    @Transactional
    public TransferResult recordRejected(String idempotencyKey, String requestHash,
                                         UUID fromAccountId, UUID toAccountId,
                                         long amountCents, TriageDecision decision) {
        if (decision.outcome().postsToLedger()) {
            throw new IllegalStateException("recordRejected called with outcome " + decision.outcome());
        }
        TransferDecision row = decisionRepository.save(new TransferDecision(
                idempotencyKey, requestHash, fromAccountId, toAccountId, amountCents, decision, null));
        return new TransferResult(decision.outcome(), row.getId(), null);
    }
}
