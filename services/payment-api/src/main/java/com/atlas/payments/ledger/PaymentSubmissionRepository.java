package com.atlas.payments.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentSubmissionRepository extends JpaRepository<PaymentSubmissionEntity, Long> {
    @Query("""
            SELECT s FROM PaymentSubmissionEntity s
              JOIN FETCH s.journalEntry
             WHERE s.idempotencyKey = :idempotencyKey
            """)
    Optional<PaymentSubmissionEntity> findByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);
}
