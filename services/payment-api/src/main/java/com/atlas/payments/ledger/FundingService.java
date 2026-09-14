package com.atlas.payments.ledger;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

@Service
public class FundingService {
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

        entry.addPosting(settlement, amountMinor, currencyCode);
        entry.addPosting(customer, -amountMinor, currencyCode);

        journalEntries.save(entry);
    }

    AccountEntity settlementAccount(String currencyCode, Instant now) {
        String number = SETTLEMENT_ACCOUNT_PREFIX + currencyCode;
        try {
            accountProvisioner.createIfAbsent(number, currencyCode, AccountType.SETTLEMENT, now);
        } catch (DataIntegrityViolationException lostTheRace) {
        }
        return accounts.findByAccountNumber(number).orElseThrow(() -> new IllegalStateException(
                "settlement account " + number + " must exist after provisioning"));
    }

    private AccountEntity customerAccount(String accountNumber, String currencyCode, Instant now) {
        try {
            accountProvisioner.createIfAbsent(accountNumber, currencyCode, AccountType.CUSTOMER, now);
        } catch (DataIntegrityViolationException lostTheRace) {
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
