package com.atlas.payments.ledger;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JournalEntryRepository extends JpaRepository<JournalEntryEntity, Long> {
    Page<JournalEntryEntity> findAllByOrderByBookedAtDesc(Pageable pageable);

    Optional<JournalEntryEntity> findByExternalId(UUID externalId);

    /**
     * Loads postings and their accounts for an already-paged set of entries.
     * Both associations are lazy, so reading them per entry costs a query each:
     * a 50-row page was issuing over 150. Fetch-joining in the paged query
     * instead would make Hibernate paginate the collection in memory, hence
     * two queries rather than one.
     */
    @Query("""
            SELECT DISTINCT e
              FROM JournalEntryEntity e
              LEFT JOIN FETCH e.postings p
              LEFT JOIN FETCH p.account
             WHERE e.id IN :ids
            """)
    List<JournalEntryEntity> findWithPostingsByIdIn(@Param("ids") Collection<Long> ids);

    @Query("""
            SELECT p.journalEntry.id
              FROM PostingEntity p
             GROUP BY p.journalEntry.id
            HAVING SUM(p.amountMinor) <> 0
            """)
    List<Long> findUnbalancedEntryIds();
}
