package com.atlas.payments.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One leg of a journal entry.
 *
 * <p>{@code amountMinor} is a signed count of the currency's minor unit:
 * positive is a debit, negative is a credit. One signed column rather than
 * separate debit and credit columns, so that "this entry balances" is the single
 * expression {@code SUM(amount_minor) = 0} rather than a comparison of two sums
 * that can disagree about NULL.
 */
@Entity
@Table(name = "postings")
public class PostingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_entry_id", nullable = false)
    private JournalEntryEntity journalEntry;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private AccountEntity account;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    protected PostingEntity() {
    }

    PostingEntity(JournalEntryEntity journalEntry, AccountEntity account, long amountMinor, String currency) {
        this.journalEntry = journalEntry;
        this.account = account;
        this.amountMinor = amountMinor;
        this.currency = currency;
    }

    public Long getId() {
        return id;
    }

    public AccountEntity getAccount() {
        return account;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public boolean isDebit() {
        return amountMinor > 0;
    }
}
