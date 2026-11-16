package com.karimsaleh.ledger.transaction;

import com.karimsaleh.ledger.TestcontainersConfiguration;
import com.karimsaleh.ledger.account.Account;
import com.karimsaleh.ledger.account.AccountRepository;
import com.karimsaleh.ledger.account.AccountType;
import com.karimsaleh.ledger.fraud.FakeJevClient;
import com.karimsaleh.ledger.fraud.JevUnavailableException;
import com.karimsaleh.ledger.fraud.ReasonFlag;
import com.karimsaleh.ledger.fraud.SyntheticTransfers;
import com.karimsaleh.ledger.fraud.TransferDecision;
import com.karimsaleh.ledger.fraud.TransferDecisionRepository;
import com.karimsaleh.ledger.fraud.TriageOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end triage tests against a real Postgres. Jev is always the {@link FakeJevClient};
 * nothing here can reach the network.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TransferTriageIntegrationTest {

    private static final long FUNDING_CENTS = 1_000_000;

    @Autowired private TransferService transferService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransferDecisionRepository decisionRepository;
    @Autowired private FakeJevClient fakeJev;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;

    private Account funding;
    private Account alice;
    private Account bob;
    private UUID depositTransactionId;

    @BeforeEach
    void setUp() {
        fakeJev.reset();
        SyntheticTransfers.all().forEach(f -> fakeJev.willReturnFor(f.amountCents(), f.assessment()));

        funding = accountRepository.save(new Account("Funding", AccountType.SYSTEM, "CAD"));
        alice = accountRepository.save(new Account("Alice", AccountType.USER, "CAD"));
        bob = accountRepository.save(new Account("Bob", AccountType.USER, "CAD"));

        TransferResult deposit = transferService.transfer(
                key(), funding.getId(), alice.getId(), FUNDING_CENTS, "Initial funding");
        depositTransactionId = deposit.transaction().getId();
    }

    @AfterEach
    void ledgerInvariantsHold() {
        assertThat(count("SELECT COALESCE(SUM(amount_cents), 0) FROM entries"))
                .as("sum of all entries").isZero();
        assertThat(count("SELECT count(*) FROM (SELECT 1 FROM entries GROUP BY transaction_id "
                + "HAVING SUM(amount_cents) <> 0 OR count(*) <> 2) t"))
                .as("transactions whose entries don't sum to 0 or aren't exactly 2").isZero();
        assertThat(count("SELECT count(*) FROM (SELECT 1 FROM accounts a JOIN entries e ON e.account_id = a.id "
                + "WHERE a.type = 'USER' GROUP BY a.id HAVING SUM(e.amount_cents) < 0) t"))
                .as("USER accounts with a negative balance").isZero();
        assertThat(count("SELECT count(*) FROM transfer_decisions "
                + "WHERE outcome IN ('HELD', 'BLOCKED') AND transaction_id IS NOT NULL"))
                .as("held/blocked decisions that posted to the ledger").isZero();
    }

    // ---- approve ----

    @Test
    void approvedTransferPostsTwoBalancedEntries() throws Exception {
        long entriesBefore = count("SELECT count(*) FROM entries");
        String key = key();

        TransferResult result = transferService.transfer(
                key, alice.getId(), bob.getId(), SyntheticTransfers.NORMAL.amountCents(), "Dinner");

        assertThat(result.outcome()).isEqualTo(TriageOutcome.APPROVED);
        assertThat(result.transaction()).isNotNull();
        assertThat(count("SELECT count(*) FROM entries")).isEqualTo(entriesBefore + 2);
        assertThat(count("SELECT SUM(amount_cents) FROM entries WHERE transaction_id = ?",
                result.transaction().getId())).isZero();
        assertThat(count("SELECT count(*) FROM entries WHERE transaction_id = ?",
                result.transaction().getId())).isEqualTo(2);

        TransferDecision decision = decisionRepository.findByIdempotencyKey(key).orElseThrow();
        assertThat(decision.getId()).isEqualTo(result.decisionId());
        assertThat(decision.getOutcome()).isEqualTo(TriageOutcome.APPROVED);
        assertThat(decision.getTransactionId()).isEqualTo(result.transaction().getId());
        assertThat(fakeJev.callCount()).isEqualTo(1);

        postTransfer(key(), SyntheticTransfers.NORMAL.amountCents()).andExpect(status().isCreated());
    }

    // ---- block ----

    @Test
    void blockedTransferPostsNothing() throws Exception {
        long aliceBefore = balance(alice);
        long bobBefore = balance(bob);
        long entriesBefore = count("SELECT count(*) FROM entries");
        long transactionsBefore = count("SELECT count(*) FROM transactions");
        String key = key();

        TransferResult result = transferService.transfer(
                key, alice.getId(), bob.getId(), SyntheticTransfers.ACCOUNT_TAKEOVER.amountCents(), "Suspicious");

        assertThat(result.outcome()).isEqualTo(TriageOutcome.BLOCKED);
        assertThat(result.transaction()).isNull();

        TransferDecision decision = decisionRepository.findByIdempotencyKey(key).orElseThrow();
        assertThat(decision.getId()).isEqualTo(result.decisionId());
        assertThat(decision.getOutcome()).isEqualTo(TriageOutcome.BLOCKED);
        assertThat(decision.getReasonCode()).isEqualTo("HIGH_RISK");
        assertThat(decision.getReasons())
                .containsExactly(ReasonFlag.UNUSUAL_AMOUNT, ReasonFlag.NEW_RECIPIENT, ReasonFlag.ODD_TIME);
        assertThat(decision.getTransactionId()).isNull();

        String body = postTransfer(key(), SyntheticTransfers.ACCOUNT_TAKEOVER.amountCents())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.decisionId").exists())
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andReturn().getResponse().getContentAsString().toLowerCase(Locale.ROOT);
        for (ReasonFlag flag : ReasonFlag.values()) {
            assertThat(body).doesNotContain(flag.name().toLowerCase(Locale.ROOT))
                    .doesNotContain(flag.questionId());
        }
        assertThat(body).doesNotContain("reasons").doesNotContain("high_risk");

        assertThat(count("SELECT count(*) FROM entries")).isEqualTo(entriesBefore);
        assertThat(count("SELECT count(*) FROM transactions")).isEqualTo(transactionsBefore);
        assertThat(balance(alice)).isEqualTo(aliceBefore);
        assertThat(balance(bob)).isEqualTo(bobBefore);
    }

    // ---- hold ----

    @Test
    void heldTransferPostsNothingAndReturnsPendingReview() throws Exception {
        long entriesBefore = count("SELECT count(*) FROM entries");
        String key = key();

        TransferResult result = transferService.transfer(
                key, alice.getId(), bob.getId(), SyntheticTransfers.AMBIGUOUS.amountCents(), "Marketplace");

        assertThat(result.outcome()).isEqualTo(TriageOutcome.HELD);
        assertThat(result.transaction()).isNull();
        TransferDecision decision = decisionRepository.findByIdempotencyKey(key).orElseThrow();
        assertThat(decision.getOutcome()).isEqualTo(TriageOutcome.HELD);
        assertThat(decision.getReasonCode()).isEqualTo("LOW_CONFIDENCE");
        assertThat(decision.getTransactionId()).isNull();

        postTransfer(key(), SyntheticTransfers.AMBIGUOUS.amountCents())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.decisionId").exists())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));

        assertThat(count("SELECT count(*) FROM entries")).isEqualTo(entriesBefore);
    }

    @Test
    void confidentMediumRiskIsHeldForMediumRisk() {
        String key = key();

        TransferResult result = transferService.transfer(
                key, alice.getId(), bob.getId(), SyntheticTransfers.CONFIDENT_MEDIUM.amountCents(), "Big one");

        assertThat(result.outcome()).isEqualTo(TriageOutcome.HELD);
        assertThat(decisionRepository.findByIdempotencyKey(key).orElseThrow().getReasonCode())
                .isEqualTo("MEDIUM_RISK");
    }

    // ---- Jev failure ----

    @Test
    void jevFailureHoldsTheTransfer() {
        fakeJev.willThrow(new JevUnavailableException("simulated outage"));
        long entriesBefore = count("SELECT count(*) FROM entries");
        String key = key();

        TransferResult result = transferService.transfer(
                key, alice.getId(), bob.getId(), SyntheticTransfers.NORMAL.amountCents(), "Dinner");

        assertThat(result.outcome()).isEqualTo(TriageOutcome.HELD);
        assertThat(result.transaction()).isNull();
        TransferDecision decision = decisionRepository.findByIdempotencyKey(key).orElseThrow();
        assertThat(decision.getOutcome()).isEqualTo(TriageOutcome.HELD);
        assertThat(decision.getReasonCode()).isEqualTo("JEV_UNAVAILABLE");
        assertThat(decision.getTransactionId()).isNull();
        assertThat(count("SELECT count(*) FROM entries")).isEqualTo(entriesBefore);
    }

    // ---- idempotency ----

    @Test
    void replayingHeldTransferReturnsSameDecisionWithoutCallingJev() {
        String key = key();
        long amount = SyntheticTransfers.AMBIGUOUS.amountCents();

        TransferResult first = transferService.transfer(key, alice.getId(), bob.getId(), amount, "Marketplace");
        int callsAfterFirst = fakeJev.callCount();
        TransferResult replay = transferService.transfer(key, alice.getId(), bob.getId(), amount, "Marketplace");

        assertThat(callsAfterFirst).isEqualTo(1);
        assertThat(replay.outcome()).isEqualTo(TriageOutcome.HELD);
        assertThat(replay.decisionId()).isEqualTo(first.decisionId());
        assertThat(fakeJev.callCount()).isEqualTo(callsAfterFirst);
        assertThat(count("SELECT count(*) FROM transfer_decisions WHERE idempotency_key = ?", key)).isEqualTo(1);
    }

    @Test
    void replayingApprovedTransferReturnsSameTransactionWithoutCallingJev() {
        String key = key();
        long amount = SyntheticTransfers.NORMAL.amountCents();

        TransferResult first = transferService.transfer(key, alice.getId(), bob.getId(), amount, "Dinner");
        int callsAfterFirst = fakeJev.callCount();
        TransferResult replay = transferService.transfer(key, alice.getId(), bob.getId(), amount, "Dinner");

        assertThat(replay.outcome()).isEqualTo(TriageOutcome.APPROVED);
        assertThat(replay.transaction()).isNotNull();
        assertThat(replay.transaction().getId()).isEqualTo(first.transaction().getId());
        assertThat(count("SELECT count(*) FROM entries WHERE transaction_id = ?",
                first.transaction().getId())).isEqualTo(2);
        assertThat(fakeJev.callCount()).isEqualTo(callsAfterFirst);
        assertThat(balance(bob)).isEqualTo(amount);
    }

    @Test
    void sameKeyWithDifferentAmountIsAConflict() throws Exception {
        String key = key();

        postTransfer(key, SyntheticTransfers.NORMAL.amountCents()).andExpect(status().isCreated());
        postTransfer(key, SyntheticTransfers.NORMAL.amountCents() + 1).andExpect(status().isConflict());

        assertThat(balance(bob)).isEqualTo(SyntheticTransfers.NORMAL.amountCents());
    }

    @Test
    void sameKeyWithDifferentAmountIsAConflictForHeldTransfersToo() throws Exception {
        String key = key();

        postTransfer(key, SyntheticTransfers.AMBIGUOUS.amountCents()).andExpect(status().isAccepted());
        postTransfer(key, SyntheticTransfers.NORMAL.amountCents()).andExpect(status().isConflict());
    }

    // ---- cheap failures and deposits never call Jev ----

    @Test
    void insufficientFundsIsRejectedWithoutCallingJev() throws Exception {
        long entriesBefore = count("SELECT count(*) FROM entries");

        postTransfer(key(), FUNDING_CENTS + 1).andExpect(status().isUnprocessableEntity());

        assertThat(fakeJev.callCount()).isZero();
        assertThat(count("SELECT count(*) FROM entries")).isEqualTo(entriesBefore);
    }

    @Test
    void sameAccountTransferIsRejectedWithoutCallingJev() throws Exception {
        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(alice.getId(), alice.getId(), SyntheticTransfers.NORMAL.amountCents())))
                .andExpect(status().isBadRequest());

        assertThat(fakeJev.callCount()).isZero();
    }

    @Test
    void depositFromSystemAccountSkipsTriage() {
        TransferResult deposit = transferService.transfer(
                key(), funding.getId(), bob.getId(), 5_000, "Top-up");

        assertThat(deposit.outcome()).isEqualTo(TriageOutcome.SKIPPED);
        assertThat(deposit.transaction()).isNotNull();
        assertThat(balance(bob)).isEqualTo(5_000);
        assertThat(fakeJev.callCount()).isZero();
    }

    // ---- database guard ----

    @Test
    void databaseRefusesABlockedDecisionLinkedToATransaction() {
        String key = key();
        transferService.transfer(key, alice.getId(), bob.getId(),
                SyntheticTransfers.ACCOUNT_TAKEOVER.amountCents(), "Suspicious");
        TransferDecision blocked = decisionRepository.findByIdempotencyKey(key).orElseThrow();
        assertThat(blocked.getOutcome()).isEqualTo(TriageOutcome.BLOCKED);

        // Link the blocked decision to a real transaction (so no foreign key fails first) and
        // expect the table's own rule to refuse it, whatever the application code does.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE transfer_decisions SET transaction_id = ? WHERE id = ?",
                depositTransactionId, blocked.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> {
                    Throwable root = NestedExceptionUtils.getRootCause(e);
                    assertThat(root).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) root).getSQLState()).as("check_violation").isEqualTo("23514");
                });
    }

    // ---- helpers ----

    private ResultActions postTransfer(String key, long amountCents) throws Exception {
        return mockMvc.perform(post("/transfers")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(alice.getId(), bob.getId(), amountCents)));
    }

    private static String json(UUID from, UUID to, long amountCents) {
        return """
                {"fromAccountId":"%s","toAccountId":"%s","amountCents":%d,"description":"test"}
                """.formatted(from, to, amountCents);
    }

    private long balance(Account account) {
        return count("SELECT COALESCE(SUM(amount_cents), 0) FROM entries WHERE account_id = ?", account.getId());
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
