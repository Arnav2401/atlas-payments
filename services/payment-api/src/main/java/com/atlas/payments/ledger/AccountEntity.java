package com.atlas.payments.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * An account in the ledger.
 *
 * <p><b>Note what is absent: there is no balance field.</b> A balance is
 * {@code SUM(postings.amount_minor)} for the account. Storing a running balance
 * is simpler and faster and is precisely the mistake that makes a ledger wrong
 * under concurrency, because it turns every payment into a read-modify-write on
 * a contended row.
 *
 * <p>{@link Version} gives optimistic locking on mutation of the account row
 * itself. It is deliberately NOT bumped by posting to the account: postings are
 * append-only and independent, so incrementing a version on every posting would
 * manufacture contention between unrelated payments purely to have a lock in
 * use. The idempotency guarantee comes from the unique constraint on
 * {@code payment_submissions.idempotency_key}, not from this column.
 */
@Entity
@Table(name = "accounts")
public class AccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_number", nullable = false, unique = true)
    private String accountNumber;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 16)
    private AccountType accountType;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AccountEntity() {
    }

    public AccountEntity(String accountNumber, String currency, AccountType accountType, Instant createdAt) {
        this.accountNumber = accountNumber;
        this.currency = currency;
        this.accountType = accountType;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getCurrency() {
        return currency;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
