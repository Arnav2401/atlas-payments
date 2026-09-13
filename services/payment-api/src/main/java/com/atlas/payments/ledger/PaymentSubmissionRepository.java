package com.atlas.payments.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentSubmissionRepository extends JpaRepository<PaymentSubmissionEntity, Long> {

    /**
     * Fetch-joins the journal entry on purpose. The association is LAZY, and the
     * caller reads {@code journalEntry.externalId} after the read-only
     * transaction has closed — without the join that is a
     * LazyInitializationException, and it would only show up at runtime.
     */
    @Query("""
            SELECT s FROM PaymentSubmissionEntity s
              JOIN FETCH s.journalEntry
             WHERE s.idempotencyKey = :idempotencyKey
            """)
    Optional<PaymentSubmissionEntity> findByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);
}
