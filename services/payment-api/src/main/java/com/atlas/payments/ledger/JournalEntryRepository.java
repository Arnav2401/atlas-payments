package com.atlas.payments.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface JournalEntryRepository extends JpaRepository<JournalEntryEntity, Long> {

    /**
     * Entries whose postings do not sum to zero.
     *
     * <p>This should always return nothing, because the deferred constraint
     * trigger makes an unbalanced entry impossible to commit. It is queried
     * anyway: a reconciliation that can only ever pass proves nothing, and this
     * is the query that would catch the trigger being dropped by a future
     * migration.
     */
    @Query("""
            SELECT p.journalEntry.id
              FROM PostingEntity p
             GROUP BY p.journalEntry.id
            HAVING SUM(p.amountMinor) <> 0
            """)
    List<Long> findUnbalancedEntryIds();
}
