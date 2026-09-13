package com.atlas.payments.ledger;

import com.atlas.payments.domain.PaymentInstruction;
import com.atlas.payments.persistence.StoredPayment;
import com.atlas.payments.testing.PaymentInstructionRequests;
import com.atlas.payments.validation.PaymentValidator;
import com.atlas.payments.validation.ValidationOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ledger against a real PostgreSQL, because none of what this module claims
 * can be shown against H2 or a mock. The unique constraint, the deferred
 * constraint trigger and the concurrent-insert race are all database behaviour.
 */
@SpringBootTest
@Testcontainers
class LedgerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

    @Autowired
    private LedgerPaymentStore store;

    @Autowired
    private LedgerWriter writer;

    @Autowired
    private PaymentValidator validator;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private JournalEntryRepository journalEntries;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private PostingRepository postings;

    @Autowired
    private ReconciliationService reconciliation;

    private String debtor;
    private String creditor;

    @BeforeEach
    void uniqueAccountsPerTest() {
        // Distinct accounts per test so that the shared container's state cannot
        // make one test's assertions depend on another's ordering.
        debtor = "DE89" + UUID.randomUUID().toString().replace("-", "").substring(0, 18).toUpperCase();
        creditor = "GB29" + UUID.randomUUID().toString().replace("-", "").substring(0, 18).toUpperCase();
    }

    private PaymentInstruction instruction(String amount, String currency) {
        var request = PaymentInstructionRequests.valid()
                .instructedAmount(amount)
                .instructedCurrency(currency)
                .debtorAccount(debtor)
                .creditorAccount(creditor)
                .build();

        return ((ValidationOutcome.Accepted) validator.validate(request)).instruction();
    }

    @Test
    void a_payment_becomes_two_postings_that_sum_to_zero() {
        StoredPayment stored = store.record(instruction("100.00", "USD"), "key-" + UUID.randomUUID());

        assertFalse(stored.alreadyExisted());
        assertEquals(10_000L, accounts.balanceMinorUnits(debtor), "debit of 100.00 USD = 10000 cents");
        assertEquals(-10_000L, accounts.balanceMinorUnits(creditor));
        assertEquals(0L, accounts.balanceMinorUnits(debtor) + accounts.balanceMinorUnits(creditor));
    }

    /** JPY has no minor unit — 100 yen is 100, not 10000. */
    @Test
    void minor_units_follow_the_currency() {
        store.record(instruction("100", "JPY"), "key-" + UUID.randomUUID());

        assertEquals(100L, accounts.balanceMinorUnits(debtor));
    }

    /** BHD has three. This is the case a hard-coded "multiply by 100" gets wrong. */
    @Test
    void three_decimal_currencies_are_not_assumed_to_have_two() {
        store.record(instruction("1.234", "BHD"), "key-" + UUID.randomUUID());

        assertEquals(1_234L, accounts.balanceMinorUnits(debtor));
    }

    @Test
    void the_same_payment_submitted_twice_produces_exactly_one_ledger_effect() {
        String key = "key-" + UUID.randomUUID();
        PaymentInstruction instruction = instruction("250.00", "USD");

        StoredPayment first = store.record(instruction, key);
        StoredPayment second = store.record(instruction, key);

        assertFalse(first.alreadyExisted());
        assertTrue(second.alreadyExisted(), "the repeat must be recognised as a replay");
        assertEquals(first.paymentId(), second.paymentId(), "same payment, same identity");
        assertEquals(25_000L, accounts.balanceMinorUnits(debtor), "charged once, not twice");
    }

    /**
     * <b>The M2 test that matters.</b> Two threads submit the same payment at the
     * same moment. Exactly one ledger effect must exist, and both callers must
     * receive the same payment id.
     *
     * <p>Check-then-insert cannot pass this: under READ COMMITTED neither
     * transaction can see the other's uncommitted row, so both would find the key
     * free and both would insert. What makes it pass is the unique constraint —
     * the database picks a winner, and the loser re-reads.
     */
    @Test
    void two_concurrent_submissions_produce_one_winner_and_one_clean_replay() throws Exception {
        String key = "key-" + UUID.randomUUID();
        PaymentInstruction instruction = instruction("500.00", "USD");

        int threads = 8;
        var startTogether = new CountDownLatch(1);
        var finished = new CountDownLatch(threads);
        var results = new ArrayList<StoredPayment>();
        var failures = new ArrayList<Throwable>();
        var executor = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startTogether.await();
                    StoredPayment stored = store.record(instruction, key);
                    synchronized (results) {
                        results.add(stored);
                    }
                } catch (Throwable failure) {
                    synchronized (failures) {
                        failures.add(failure);
                    }
                } finally {
                    finished.countDown();
                }
            });
        }

        startTogether.countDown();
        assertTrue(finished.await(30, TimeUnit.SECONDS), "threads did not finish");
        executor.shutdownNow();

        assertTrue(failures.isEmpty(), "no caller may see an error: " + failures);
        assertEquals(threads, results.size());

        Set<String> paymentIds = ConcurrentHashMap.newKeySet();
        results.forEach(r -> paymentIds.add(r.paymentId()));
        assertEquals(1, paymentIds.size(), "all callers must get the same payment id: " + paymentIds);

        long winners = results.stream().filter(r -> !r.alreadyExisted()).count();
        assertEquals(1, winners, "exactly one caller may be told it created the payment");

        assertEquals(50_000L, accounts.balanceMinorUnits(debtor),
                "the account must be charged exactly once");
    }

    /**
     * Proves <em>what</em> enforces idempotency, which the test above cannot.
     *
     * <p>{@code LedgerPaymentStore} checks for an existing key before inserting.
     * That check is an optimisation, and it makes the concurrent test above
     * vulnerable to passing for the wrong reason: if the first thread happens to
     * commit before the others reach their pre-check, they short-circuit and the
     * unique constraint is never exercised. The test would be green while proving
     * nothing.
     *
     * <p>So this one calls {@link LedgerWriter#write} directly — no pre-check at
     * all — and asserts that exactly one of eight simultaneous inserts survives
     * and the rest are refused by the database. That is the guarantee, stated
     * without the optimisation in the way.
     */
    @Test
    void the_unique_constraint_is_what_enforces_idempotency_not_the_pre_check() throws Exception {
        String key = "key-" + UUID.randomUUID();
        PaymentInstruction instruction = instruction("700.00", "USD");

        int threads = 8;
        var startTogether = new CountDownLatch(1);
        var finished = new CountDownLatch(threads);
        var succeeded = new AtomicInteger();
        var refusedByConstraint = new AtomicInteger();
        var unexpected = new ArrayList<Throwable>();
        var executor = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startTogether.await();
                    writer.write(instruction, key);
                    succeeded.incrementAndGet();
                } catch (DataIntegrityViolationException refused) {
                    refusedByConstraint.incrementAndGet();
                } catch (Throwable other) {
                    synchronized (unexpected) {
                        unexpected.add(other);
                    }
                } finally {
                    finished.countDown();
                }
            });
        }

        startTogether.countDown();
        assertTrue(finished.await(30, TimeUnit.SECONDS), "threads did not finish");
        executor.shutdownNow();

        assertTrue(unexpected.isEmpty(), "only constraint violations are acceptable: " + unexpected);
        assertEquals(1, succeeded.get(), "exactly one insert may survive");
        assertEquals(threads - 1, refusedByConstraint.get(),
                "every other insert must be refused by the database, not by application logic");

        assertEquals(70_000L, accounts.balanceMinorUnits(debtor), "charged exactly once");
    }

    /**
     * The dangerous case. Returning the first payment's result for a different
     * second payment would tell the caller a payment succeeded that was never
     * made.
     */
    @Test
    void reusing_a_key_for_a_different_payment_is_refused() {
        String key = "key-" + UUID.randomUUID();
        store.record(instruction("100.00", "USD"), key);

        assertThrows(IdempotencyConflictException.class,
                () -> store.record(instruction("999.00", "USD"), key));

        assertEquals(10_000L, accounts.balanceMinorUnits(debtor), "the second payment must not post");
    }

    @Test
    void an_account_cannot_receive_a_posting_in_another_currency() {
        store.record(instruction("100.00", "USD"), "key-" + UUID.randomUUID());

        assertThrows(LedgerConflictException.class,
                () -> store.record(instruction("100", "JPY"), "key-" + UUID.randomUUID()));
    }

    /**
     * Proves the database refuses an unbalanced entry, rather than trusting the
     * application to always build one correctly. Driven through a real
     * transaction rather than a test-only hook in production code.
     *
     * <p>Note where it fails: the single posting inserts happily, because the
     * trigger is DEFERRABLE INITIALLY DEFERRED. The error arrives at COMMIT.
     * That is the behaviour that makes the invariant enforceable at all — a
     * non-deferred check would reject the first leg of every valid entry.
     */
    @Test
    void the_database_refuses_a_one_legged_entry_at_commit() {
        var transaction = new TransactionTemplate(transactionManager);

        assertThrows(Exception.class, () -> transaction.execute(status -> {
            AccountEntity account = accounts.save(new AccountEntity(debtor, "USD", Instant.now()));
            JournalEntryEntity entry = new JournalEntryEntity(
                    UUID.randomUUID(), "E2E-UNBALANCED", "deliberately one-legged", Instant.now());
            entry.addPosting(account, 1_000L, "USD");
            return journalEntries.save(entry);
        }));

        assertEquals(0L, accounts.balanceMinorUnits(debtor), "nothing may have been committed");
    }

    /** The brief's bar: reconciliation green over a large synthetic run. */
    @Test
    void reconciliation_is_green_over_ten_thousand_synthetic_payments() {
        int payments = 10_000;
        var amount = new AtomicInteger();

        for (int i = 0; i < payments; i++) {
            var request = PaymentInstructionRequests.valid()
                    .instructedAmount(new BigDecimal(amount.incrementAndGet()).movePointLeft(2))
                    .instructedCurrency("USD")
                    .debtorAccount("SYNTH-D-" + (i % 50))
                    .creditorAccount("SYNTH-C-" + (i % 37))
                    .build();

            var instruction = ((ValidationOutcome.Accepted) validator.validate(request)).instruction();
            store.record(instruction, "synthetic-" + UUID.randomUUID());
        }

        ReconciliationReport report = reconciliation.reconcile();

        assertTrue(report.balanced(), () -> "ledger does not balance: " + report);
        assertEquals(0L, report.imbalanceMinorUnits());
        assertTrue(report.unbalancedEntryIds().isEmpty());
        assertEquals(-report.totalCreditsMinorUnits(), report.totalDebitsMinorUnits(),
                "sum(debits) must equal sum(credits)");
        assertTrue(report.postingCount() >= payments * 2L);
        assertNotEquals(0L, report.totalDebitsMinorUnits(), "a ledger of zeroes would balance trivially");
    }

    @Test
    void every_posting_is_accounted_for_in_the_report() {
        store.record(instruction("12.34", "USD"), "key-" + UUID.randomUUID());

        long counted = postings.countPostings();
        assertEquals(counted, reconciliation.reconcile().postingCount());
    }
}
