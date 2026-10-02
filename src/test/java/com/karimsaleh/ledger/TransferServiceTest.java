package com.karimsaleh.ledger;

import com.karimsaleh.ledger.account.Account;
import com.karimsaleh.ledger.account.AccountRepository;
import com.karimsaleh.ledger.account.AccountType;
import com.karimsaleh.ledger.transaction.EntryRepository;
import com.karimsaleh.ledger.transaction.InsufficientFundsException;
import com.karimsaleh.ledger.transaction.LedgerTransaction;
import com.karimsaleh.ledger.transaction.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransferServiceTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private EntryRepository entryRepository;

    private Account funding;
    private Account alice;
    private Account bob;

    @BeforeEach
    void setUp() {
        funding = accountRepository.save(new Account("Funding", AccountType.SYSTEM, "CAD"));
        alice = accountRepository.save(new Account("Alice", AccountType.USER, "CAD"));
        bob = accountRepository.save(new Account("Bob", AccountType.USER, "CAD"));

        // Every test starts with Alice holding $100
        transferService.transfer(newKey(), funding.getId(), alice.getId(), 10_000, "Initial funding");
    }

    @Test
    void transferMovesMoneyBetweenAccounts() {
        transferService.transfer(newKey(), alice.getId(), bob.getId(), 2_500, "Dinner");

        assertThat(balanceOf(alice)).isEqualTo(7_500);
        assertThat(balanceOf(bob)).isEqualTo(2_500);
        assertThat(balanceOf(funding)).isEqualTo(-10_000);
    }

    @Test
    void sameIdempotencyKeyDoesNotChargeTwice() {
        String key = newKey();

        LedgerTransaction first = transferService.transfer(key, alice.getId(), bob.getId(), 2_500, "Dinner");
        LedgerTransaction retry = transferService.transfer(key, alice.getId(), bob.getId(), 2_500, "Dinner");

        assertThat(retry.getId()).isEqualTo(first.getId());
        assertThat(balanceOf(alice)).isEqualTo(7_500);
    }

    @Test
    void overdraftIsRejected() {
        assertThatThrownBy(() ->
                transferService.transfer(newKey(), alice.getId(), bob.getId(), 1_000_000, "Too much"))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(balanceOf(alice)).isEqualTo(10_000);
    }

    @Test
    void concurrentTransfersCannotOverdrawAccount() throws Exception {
        // Alice has $100. Ten threads each try to send $20 at the same moment.
        // At most five should succeed, and her balance must never go negative.
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            results.add(executor.submit(() -> {
                startSignal.await();
                try {
                    transferService.transfer(newKey(), alice.getId(), bob.getId(), 2_000, "Race");
                } catch (InsufficientFundsException ignored) {
                    // Expected for the transfers that arrive after the money runs out
                }
                return null;
            }));
        }

        startSignal.countDown();
        for (Future<?> result : results) {
            result.get();
        }
        executor.shutdown();

        assertThat(balanceOf(alice)).isGreaterThanOrEqualTo(0);
    }

    private long balanceOf(Account account) {
        return entryRepository.balanceOf(account.getId());
    }

    private String newKey() {
        return UUID.randomUUID().toString();
    }
}