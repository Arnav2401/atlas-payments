package com.atlas.payments.ledger;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One financial event. The unit of atomicity: either all of its postings exist
 * or none do, and the database refuses to commit it unless they sum to zero.
 */
@Entity
@Table(name = "journal_entries")
public class JournalEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The identifier surfaced to API callers as {@code paymentId}. */
    @Column(name = "external_id", nullable = false, unique = true)
    private UUID externalId;

    @Column(name = "end_to_end_id", nullable = false)
    private String endToEndId;

    @Column(nullable = false)
    private String description;

    @Column(name = "booked_at", nullable = false, updatable = false)
    private Instant bookedAt;

    @OneToMany(mappedBy = "journalEntry", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PostingEntity> postings = new ArrayList<>();

    protected JournalEntryEntity() {
    }

    public JournalEntryEntity(UUID externalId, String endToEndId, String description, Instant bookedAt) {
        this.externalId = externalId;
        this.endToEndId = endToEndId;
        this.description = description;
        this.bookedAt = bookedAt;
    }

    public void addPosting(AccountEntity account, long amountMinor, String currency) {
        postings.add(new PostingEntity(this, account, amountMinor, currency));
    }

    public Long getId() {
        return id;
    }

    public UUID getExternalId() {
        return externalId;
    }

    public String getEndToEndId() {
        return endToEndId;
    }

    public Instant getBookedAt() {
        return bookedAt;
    }

    public List<PostingEntity> getPostings() {
        return List.copyOf(postings);
    }
}
