package com.atlas.payments.ledger;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/**
 * Puts money into a customer account, against the bank's settlement position.
 *
 * <p>Exists because an available-funds check is meaningless if no account can
 * ever hold anything. A funding entry is an ordinary balanced journal entry: it
 * debits the settlement account and credits the customer, so the ledger stays
 * balanced and reconciliation still proves it.
 *
 * <p><b>This is an operations affordance, not a product feature.</b> In a real
 * system money enters through a settlement rail — an incoming wire, a card
 * capture, a cash deposit — each with its own reconciliation against an external
 * statement. Nothing here is authenticated yet either; M5's RBAC must put this
 * behind the supervisor role, because as it stands it creates money from the
 * bank's own position on request.
 */
@Service
public class FundingService {

    /** One settlement account per currency; an entry may never mix currencies. */
    static final String SETTLEMENT_ACCOUNT_PREFIX = "ATLAS-SETTLEMENT-";

    private final AccountRepository accounts;
    private final AccountProvisioner accountProvisioner;
    private final JournalEntryRepository journalEntries;
    private final Clock clock;

    public FundingService(AccountRepository accounts, AccountProvisioner accountProvisioner,
                          JournalEntryRepository journalEntries, Clock clock) {
        this.accounts = accounts;
        this.accountProvisioner = accountProvisioner;
        this.journalEntries = journalEntries;
        this.clock = clock;
    }

    @Transactional
    public void fund(String accountNumber, BigDecimal amount, Currency currency) {
        String currencyCode = currency.getCurrencyCode();
        long amountMinor = Money.toMinorUnits(amount, currency);
        if (amountMinor <= 0) {
            throw new LedgerConflictException("funding amount must be positive");
        }

        Instant now = Instant.now(clock);
        AccountEntity settlement = settlementAccount(currencyCode, now);
        AccountEntity customer = customerAccount(accountNumber, currencyCode, now);

        JournalEntryEntity entry = new JournalEntryEntity(
                UUID.randomUUID(), "FUNDING-" + UUID.randomUUID(), "account funding", now);

        // Debit the bank's position, credit the customer. Credit is negative, so
        // the customer's available funds (the negation) go up.
        entry.addPosting(settlement, amountMinor, currencyCode);
        entry.addPosting(customer, -amountMinor, currencyCode);

        journalEntries.save(entry);
    }

    /**
     * Same two-step shape as {@link LedgerWriter#resolveAccount}, for the same
     * reason: provisioning and reading are separate calls, so that a concurrent
     * creation race is resolved by {@link AccountProvisioner} rather than by
     * whichever of two simultaneous funding calls happens to insert first. The
     * catch here is what makes that true — see {@link AccountProvisioner}'s
     * class javadoc for the version of this class that got it wrong.
     */
    AccountEntity settlementAccount(String currencyCode, Instant now) {
        String number = SETTLEMENT_ACCOUNT_PREFIX + currencyCode;
        try {
            accountProvisioner.createIfAbsent(number, currencyCode, AccountType.SETTLEMENT, now);
        } catch (DataIntegrityViolationException lostTheRace) {
            // Another funding call created it first. The row exists; proceed.
        }
        return accounts.findByAccountNumber(number).orElseThrow(() -> new IllegalStateException(
                "settlement account " + number + " must exist after provisioning"));
    }

    private AccountEntity customerAccount(String accountNumber, String currencyCode, Instant now) {
        try {
            accountProvisioner.createIfAbsent(accountNumber, currencyCode, AccountType.CUSTOMER, now);
        } catch (DataIntegrityViolationException lostTheRace) {
            // Another funding call created it first. The row exists; proceed.
        }
        AccountEntity existing = accounts.findByAccountNumber(accountNumber).orElseThrow(
                () -> new IllegalStateException(
                        "account " + accountNumber + " must exist after provisioning"));

        if (!existing.getCurrency().equals(currencyCode)) {
            throw new LedgerConflictException(
                    "account " + accountNumber + " is held in " + existing.getCurrency());
        }
        return existing;
    }
}
