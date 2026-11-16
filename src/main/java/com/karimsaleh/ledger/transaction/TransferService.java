package com.karimsaleh.ledger.transaction;

import com.karimsaleh.ledger.account.Account;
import com.karimsaleh.ledger.account.AccountRepository;
import com.karimsaleh.ledger.account.AccountType;
import com.karimsaleh.ledger.fraud.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates a transfer: validate, triage with Jev, then post or record the rejection.
 *
 * <p>Deliberately NOT {@code @Transactional}. The Jev call takes 1-2 seconds, and
 * {@code LedgerPostingService.post} holds {@code SELECT ... FOR UPDATE} row locks. Calling Jev
 * inside that transaction would block every other transfer touching those accounts and tie up
 * DB connections for the whole network round trip. So Jev runs first with no locks, and the
 * short locked transaction happens afterwards.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final EntryRepository entryRepository;
    private final TransferDecisionRepository decisionRepository;
    private final TransferFeatureBuilder featureBuilder;
    private final JevClient jevClient;
    private final FraudTriagePolicy policy;
    private final FraudTriageProperties props;
    private final LedgerPostingService postingService;

    public TransferService(AccountRepository accountRepository,
                           LedgerTransactionRepository transactionRepository,
                           EntryRepository entryRepository,
                           TransferDecisionRepository decisionRepository,
                           TransferFeatureBuilder featureBuilder,
                           JevClient jevClient,
                           FraudTriagePolicy policy,
                           FraudTriageProperties props,
                           LedgerPostingService postingService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.decisionRepository = decisionRepository;
        this.featureBuilder = featureBuilder;
        this.jevClient = jevClient;
        this.policy = policy;
        this.props = props;
        this.postingService = postingService;
    }

    public TransferResult transfer(String idempotencyKey,
                                   UUID fromAccountId,
                                   UUID toAccountId,
                                   long amountCents,
                                   String description) {

        String requestHash = hash(fromAccountId, toAccountId, amountCents, description);

        Optional<TransferResult> replay = replay(idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        // Cheap validation, no locks. Failures here never reach Jev.
        if (amountCents <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (fromAccountId.equals(toAccountId)) {
            throw new IllegalArgumentException("Cannot transfer to the same account");
        }
        Account from = accountRepository.findById(fromAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + fromAccountId));
        Account to = accountRepository.findById(toAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + toAccountId));
        if (!from.getCurrency().equals(to.getCurrency())) {
            throw new IllegalArgumentException("Currency mismatch: " + from.getCurrency() + " vs " + to.getCurrency());
        }
        if (from.getType() == AccountType.USER) {
            long balance = entryRepository.balanceOf(fromAccountId);   // unlocked; re-checked under lock in post()
            if (balance < amountCents) {
                throw new InsufficientFundsException(
                        "Insufficient funds: balance " + balance + ", requested " + amountCents);
            }
        }

        TriageDecision decision = triage(from, to, amountCents);
        logDecision(idempotencyKey, decision);

        try {
            if (decision.outcome().postsToLedger()) {
                return postingService.post(idempotencyKey, requestHash, fromAccountId, toAccountId,
                        amountCents, description, decision);
            }
            return postingService.recordRejected(idempotencyKey, requestHash, fromAccountId, toAccountId,
                    amountCents, decision);
        } catch (DataIntegrityViolationException e) {
            // Only a duplicate if a decision row with this key now exists (a concurrent request won
            // the race). Replay it, or 409 if the hash differs. Any other violation (e.g.
            // posted_iff_allowed) is a real bug or data problem and must propagate.
            return replayDecision(idempotencyKey, requestHash).orElseThrow(() -> e);
        }
    }

    private TriageDecision triage(Account from, Account to, long amountCents) {
        if (from.getType() == AccountType.SYSTEM && props.triage().skipSystemSources()) {
            return new TriageDecision(TriageOutcome.SKIPPED, "SYSTEM_SOURCE", java.util.List.of(), null);
        }
        try {
            TransferFeatures features = featureBuilder.build(from, to, amountCents);
            return policy.decide(jevClient.assess(features));
        } catch (JevUnavailableException e) {
            return policy.onFailure(e);
        } catch (RuntimeException e) {
            // Anything unexpected while triaging must still fail closed: HOLD, never approve.
            return policy.onFailure(new JevUnavailableException("Unexpected triage error", e));
        }
    }

    private Optional<TransferResult> replay(String key, String requestHash) {
        Optional<TransferResult> fromDecision = replayDecision(key, requestHash);
        if (fromDecision.isPresent()) {
            return fromDecision;
        }
        // Legacy: transaction created before the triage feature, so no decision row exists.
        return transactionRepository.findByIdempotencyKey(key)
                .map(tx -> new TransferResult(TriageOutcome.SKIPPED, null, tx));
    }

    /** Replays the stored decision for this key, if any. Throws IdempotencyConflictException on a hash mismatch. */
    private Optional<TransferResult> replayDecision(String key, String requestHash) {
        return decisionRepository.findByIdempotencyKey(key).map(d -> {
            if (!d.getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflictException("Idempotency-Key was already used with a different request");
            }
            LedgerTransaction tx = null;
            if (d.getOutcome().postsToLedger()) {
                tx = transactionRepository.findByIdempotencyKey(key)
                        .orElseThrow(() -> new IllegalStateException("Decision without transaction: " + d.getId()));
            }
            return new TransferResult(d.getOutcome(), d.getId(), tx);
        });
    }

    private void logDecision(String key, TriageDecision d) {
        JevAssessment a = d.assessment();
        log.info("triage key={} outcome={} reasonCode={} reasons={} riskLevel={} confidence={} latencyMs={}",
                key, d.outcome(), d.reasonCode(), d.reasons(),
                a == null ? null : a.riskLevel(),
                a == null ? null : a.confidence(),
                a == null ? null : a.latencyMs());
    }

    private static String hash(UUID from, UUID to, long amountCents, String description) {
        String canonical = from + "|" + to + "|" + amountCents + "|" + (description == null ? "" : description);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
