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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ledger against a real PostgreSQL, because nothing this module claims can be
 * shown against H2 or a mock. The unique constraint, the deferred constraint
 * trigger, the version check and the insert race are all database behaviour.
 */
@SpringBootTest
@Testcontainers
class LedgerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

    private static final Currency USD = Currency.getInstance("USD");

    @Autowired private LedgerPaymentStore store;
    @Autowired private LedgerWriter writer;
    @Autowired private FundingService funding;
    @Autowired private PaymentValidator validator;
    @Autowired private AccountRepository accounts;
    @Autowired private JournalEntryRepository journalEntries;
    @Autowired private PostingRepository postings;
    @Autowired private ReconciliationService reconciliation;
    @Autowired private PlatformTransactionManager transactionManager;

    private String debtor;
    private String creditor;

    @BeforeEach
    void uniqueAccountsPerTest() {
        // Distinct accounts per test, so the shared container's accumulated state
        // cannot make one test's assertions depend on another's ordering.
        debtor = "DE89" + randomSuffix();
        creditor = "GB29" + randomSuffix();
    }

    private static String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 18).toUpperCase();
    }

    private PaymentInstruction instruction(String amount, String currencyCode) {
        var request = PaymentInstructionRequests.valid()
                .instructedAmount(amount)
                .instructedCurrency(currencyCode)
                .debtorAccount(debtor)
                .creditorAccount(creditor)
                .build();

        return ((ValidationOutcome.Accepted) validator.validate(request)).instruction();
    }

    private String freshKey() {
        return "key-" + UUID.randomUUID();
    }

    // ----------------------------------------------------------------- postings

    @Test
    void a_payment_moves_funds_and_the_ledger_still_balances() {
        funding.fund(debtor, new BigDecimal("1000.00"), USD);

        StoredPayment stored = store.record(instruction("100.00", "USD"), freshKey());

        assertFalse(stored.alreadyExisted());
        assertEquals(90_000L, accounts.availableMinorUnits(debtor), "1000.00 - 100.00 = 900.00");
        assertEquals(10_000L, accounts.availableMinorUnits(creditor));
        assertTrue(reconciliation.reconcile().balanced());
    }

    /** JPY has no minor unit — 100 yen is 100, not 10000. */
    @Test
    void minor_units_follow_the_currency() {
        funding.fund(debtor, new BigDecimal("1000"), Currency.getInstance("JPY"));

        store.record(instruction("100", "JPY"), freshKey());

        assertEquals(900L, accounts.availableMinorUnits(debtor));
    }

    /** BHD has three. This is the case a hard-coded "multiply by 100" gets wrong. */
    @Test
    void three_decimal_currencies_are_not_assumed_to_have_two() {
        funding.fund(debtor, new BigDecimal("10.000"), Currency.getInstance("BHD"));

        store.record(instruction("1.234", "BHD"), freshKey());

        assertEquals(8_766L, accounts.availableMinorUnits(debtor), "10000 - 1234 minor units");
    }

    // -------------------------------------------------------------- idempotency

    @Test
    void the_same_payment_submitted_twice_produces_exactly_one_ledger_effect() {
        funding.fund(debtor, new BigDecimal("1000.00"), USD);
        String key = freshKey();
        PaymentInstruction instruction = instruction("250.00", "USD");

        StoredPayment first = store.record(instruction, key);
        StoredPayment second = store.record(instruction, key);

        assertFalse(first.alreadyExisted());
        assertTrue(second.alreadyExisted(), "the repeat must be recognised as a replay");
        assertEquals(first.paymentId(), second.paymentId(), "same payment, same identity");
        assertEquals(75_000L, accounts.availableMinorUnits(debtor), "charged once, not twice");
    }

    /**
     * Deterministic proof that the unique constraint is real, with no race to
     * depend on: bypass the store's pre-check and insert the same key twice.
     *
     * <p>The concurrent test below is the interesting one, but it can pass for the
     * wrong reason — if the first thread commits before the others reach the
     * pre-check, they short-circuit and the constraint is never exercised. This
     * test cannot.
     */
    @Test
    void a_repeat_insert_of_the_same_key_is_refused_by_the_database() {
        funding.fund(debtor, new BigDecimal("1000.00"), USD);
        String key = freshKey();

        writer.write(instruction("100.00", "USD"), key);

        assertThrows(DataIntegrityViolationException.class,
                () -> writer.write(instruction("100.00", "USD"), key));
        assertEquals(90_000L, accounts.availableMinorUnits(debtor), "charged once");
    }

    /**
     * <b>The M2 idempotency test.</b> Eight threads submit the same payment at the
     * same moment. Exactly one ledger effect must exist and every caller must
     * receive the same payment id.
     *
     * <p>Check-then-insert cannot pass this: under READ COMMITTED neither
     * transaction can see the other's uncommitted row, so all eight would find the
     * key free and all eight would insert.
     */
    @Test
    void concurrent_submissions_produce_one_winner_and_seven_clean_replays() throws Exception {
        funding.fund(debtor, new BigDecimal("10000.00"), USD);
        String key = freshKey();
        PaymentInstruction instruction = instruction("500.00", "USD");

        Outcomes outcomes = runConcurrently(8, () -> store.record(instruction, key));

        assertTrue(outcomes.failures().isEmpty(), "no caller may see an error: " + outcomes.failures());
        assertEquals(8, outcomes.results().size());

        Set<String> paymentIds = ConcurrentHashMap.newKeySet();
        outcomes.results().forEach(stored -> paymentIds.add(stored.paymentId()));
        assertEquals(1, paymentIds.size(), "all callers must get the same payment id: " + paymentIds);

        long winners = outcomes.results().stream().filter(r -> !r.alreadyExisted()).count();
        assertEquals(1, winners, "exactly one caller may be told it created the payment");

        assertEquals(950_000L, accounts.availableMinorUnits(debtor), "charged exactly once");
    }

    /**
     * The dangerous case. Returning the first payment's result for a different
     * second payment would tell the caller a payment succeeded that was never made.
     */
    @Test
    void reusing_a_key_for_a_different_payment_is_refused() {
        funding.fund(debtor, new BigDecimal("2000.00"), USD);
        String key = freshKey();
        store.record(instruction("100.00", "USD"), key);

        assertThrows(IdempotencyConflictException.class,
                () -> store.record(instruction("999.00", "USD"), key));

        assertEquals(190_000L, accounts.availableMinorUnits(debtor), "the second payment must not post");
    }

    // ------------------------------------------- available funds / optimistic lock

    @Test
    void a_payment_beyond_the_available_balance_is_refused() {
        funding.fund(debtor, new BigDecimal("50.00"), USD);

        InsufficientFundsException insufficient = assertThrows(InsufficientFundsException.class,
                () -> store.record(instruction("100.00", "USD"), freshKey()));

        assertEquals(5_000L, insufficient.getAvailableMinorUnits());
        assertEquals(10_000L, insufficient.getRequiredMinorUnits());
        assertEquals(5_000L, accounts.availableMinorUnits(debtor), "nothing may have posted");
    }

    @Test
    void spending_the_exact_balance_is_allowed() {
        funding.fund(debtor, new BigDecimal("50.00"), USD);

        store.record(instruction("50.00", "USD"), freshKey());

        assertEquals(0L, accounts.availableMinorUnits(debtor));
    }

    /**
     * <b>The reason {@code accounts.version} exists.</b> Eight threads each try to
     * spend the entire balance under eight <em>different</em> idempotency keys — so
     * the unique constraint offers no protection here at all. Each payment is
     * individually affordable; together they are eight times the balance.
     *
     * <p>Without the forced version increment this is a textbook lost update: every
     * thread reads the same sufficient balance, every thread decides the payment is
     * affordable, and several commit. Under READ COMMITTED nothing stops them,
     * because no row was modified that anyone could conflict on — which is exactly
     * why a plain optimistic lock would not have helped either.
     *
     * <p>The assertion is the invariant that matters — the account cannot go
     * negative — rather than a count of which exception each loser saw, which is
     * timing-dependent.
     */
    @Test
    void concurrent_payments_can_never_overdraw_the_account() throws Exception {
        funding.fund(debtor, new BigDecimal("100.00"), USD);

        Outcomes outcomes = runConcurrently(8,
                () -> store.record(instruction("100.00", "USD"), freshKey()));

        assertEquals(1, outcomes.results().size(),
                "exactly one payment may succeed; the rest must be refused");
        assertEquals(7, outcomes.failures().size());
        assertEquals(0L, accounts.availableMinorUnits(debtor), "the account must land at exactly zero");

        // Every refusal must be a considered one: either the version check lost the
        // race, or the funds check saw the money already gone. Anything else means
        // something failed for a reason the design did not account for.
        outcomes.failures().forEach(failure -> assertTrue(
                failure instanceof OptimisticLockingFailureException
                        || failure instanceof InsufficientFundsException,
                "unexpected failure type: " + failure));

        assertTrue(reconciliation.reconcile().balanced());
    }

    /**
     * A third, distinct concurrency hazard, found by the test above failing for
     * the wrong reason on the first attempt: this test's {@code creditor} was
     * never funded, so eight threads paying eight <em>different</em> new
     * debtors to that one new, not-yet-existing creditor all raced to create the
     * same account row. That has nothing to do with idempotency or with funds —
     * it is any two first-time payments to a shared new counterparty, arriving
     * together. {@link AccountProvisioner} exists because of this test.
     */
    @Test
    void concurrent_first_payments_to_a_new_shared_counterparty_do_not_collide() throws Exception {
        int threads = 8;
        String[] freshDebtors = new String[threads];
        for (int i = 0; i < threads; i++) {
            freshDebtors[i] = "DE89" + randomSuffix();
            funding.fund(freshDebtors[i], new BigDecimal("100.00"), USD);
        }
        String sharedNewCreditor = creditor; // never funded, never referenced yet

        var startTogether = new CountDownLatch(1);
        var finished = new CountDownLatch(threads);
        var failures = new ArrayList<Throwable>();
        var executor = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            String debtorAccount = freshDebtors[i];
            executor.submit(() -> {
                try {
                    startTogether.await();
                    var request = PaymentInstructionRequests.valid()
                            .instructedAmount("10.00")
                            .instructedCurrency("USD")
                            .debtorAccount(debtorAccount)
                            .creditorAccount(sharedNewCreditor)
                            .build();
                    var instruction = ((ValidationOutcome.Accepted) validator.validate(request)).instruction();
                    store.record(instruction, "key-" + UUID.randomUUID());
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

        assertTrue(failures.isEmpty(), "no first-time payment to a new counterparty may fail: " + failures);
        assertEquals(80_00L, accounts.availableMinorUnits(sharedNewCreditor),
                "all eight payments of 10.00 must have landed");
        assertTrue(reconciliation.reconcile().balanced());
    }

    @Test
    void the_settlement_account_is_exempt_from_the_funds_check() {
        // Funding draws against the bank's own position, which must be allowed to
        // run negative — otherwise the very first funding entry would be refused.
        funding.fund(debtor, new BigDecimal("100.00"), USD);

        assertTrue(accounts.balanceMinorUnits(FundingService.SETTLEMENT_ACCOUNT_PREFIX + "USD") > 0,
                "the settlement account carries the debit side of every funding entry");
        assertTrue(reconciliation.reconcile().balanced());
    }

    @Test
    void an_account_cannot_receive_a_posting_in_another_currency() {
        funding.fund(debtor, new BigDecimal("1000.00"), USD);

        assertThrows(LedgerConflictException.class,
                () -> store.record(instruction("100", "JPY"), freshKey()));
    }

    // ------------------------------------------------------- database invariants

    /**
     * Proves the database refuses an unbalanced entry rather than trusting the
     * application to always build one correctly.
     *
     * <p>Note <em>where</em> it fails: the single posting inserts happily, because
     * the trigger is DEFERRABLE INITIALLY DEFERRED. The error arrives at COMMIT.
     * That deferral is what makes the invariant enforceable at all — a non-deferred
     * check would reject the first leg of every valid entry.
     */
    @Test
    void the_database_refuses_a_one_legged_entry_at_commit() {
        var transaction = new TransactionTemplate(transactionManager);

        assertThrows(Exception.class, () -> transaction.execute(status -> {
            AccountEntity account = accounts.save(
                    new AccountEntity(debtor, "USD", AccountType.CUSTOMER, Instant.now()));
            JournalEntryEntity entry = new JournalEntryEntity(
                    UUID.randomUUID(), "E2E-UNBALANCED", "deliberately one-legged", Instant.now());
            entry.addPosting(account, 1_000L, "USD");
            return journalEntries.save(entry);
        }));

        assertEquals(0L, accounts.balanceMinorUnits(debtor), "nothing may have been committed");
    }

    // ----------------------------------------------------------- reconciliation

    /** The brief's bar: reconciliation green over a large synthetic run. */
    @Test
    void reconciliation_is_green_over_ten_thousand_synthetic_payments() {
        int payments = 10_000;
        int debtorAccounts = 50;

        for (int account = 0; account < debtorAccounts; account++) {
            funding.fund("SYNTH-D-" + account, new BigDecimal("10000.00"), USD);
        }

        for (int i = 0; i < payments; i++) {
            var request = PaymentInstructionRequests.valid()
                    .instructedAmount(new BigDecimal((i % 100) + 1).movePointLeft(2))
                    .instructedCurrency("USD")
                    .debtorAccount("SYNTH-D-" + (i % debtorAccounts))
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
        funding.fund(debtor, new BigDecimal("100.00"), USD);
        store.record(instruction("12.34", "USD"), freshKey());

        assertEquals(postings.countPostings(), reconciliation.reconcile().postingCount());
    }

    // -------------------------------------------------------------------- helpers

    private record Outcomes(List<StoredPayment> results, List<Throwable> failures) {}

    /** Releases every thread at the same instant, so the race is a real one. */
    private Outcomes runConcurrently(int threads, Callable<StoredPayment> action) throws Exception {
        var startTogether = new CountDownLatch(1);
        var finished = new CountDownLatch(threads);
        var results = new ArrayList<StoredPayment>();
        var failures = new ArrayList<Throwable>();
        var executor = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startTogether.await();
                    StoredPayment stored = action.call();
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
        assertTrue(finished.await(60, TimeUnit.SECONDS), "threads did not finish");
        executor.shutdownNow();

        return new Outcomes(results, failures);
    }
}
